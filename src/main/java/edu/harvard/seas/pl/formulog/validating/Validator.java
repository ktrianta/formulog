package edu.harvard.seas.pl.formulog.validating;

/*-
 * #%L
 * FormuLog
 * %%
 * Copyright (C) 2018 - 2019 President and Fellows of Harvard College
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.TimeUnit;

import edu.harvard.seas.pl.formulog.ast.Atoms;
import edu.harvard.seas.pl.formulog.ast.BasicRule;
import edu.harvard.seas.pl.formulog.ast.Constructor;
import edu.harvard.seas.pl.formulog.ast.Constructors;
import edu.harvard.seas.pl.formulog.ast.Primitive;
import edu.harvard.seas.pl.formulog.ast.Program;
import edu.harvard.seas.pl.formulog.ast.Rule;
import edu.harvard.seas.pl.formulog.ast.Term;
import edu.harvard.seas.pl.formulog.ast.Terms;
import edu.harvard.seas.pl.formulog.ast.Var;
import edu.harvard.seas.pl.formulog.ast.Atoms.Atom;
import edu.harvard.seas.pl.formulog.ast.Atoms.UnifyAtom;
import edu.harvard.seas.pl.formulog.ast.FunctionCallFactory.FunctionCall;
import edu.harvard.seas.pl.formulog.ast.Terms.TermVisitor;
import edu.harvard.seas.pl.formulog.ast.Terms.TermVisitorExn;
import edu.harvard.seas.pl.formulog.ast.functions.CustomFunctionDef;
import edu.harvard.seas.pl.formulog.ast.functions.FunctionDef;
import edu.harvard.seas.pl.formulog.ast.functions.CustomFunctionDef.Expr;
import edu.harvard.seas.pl.formulog.ast.functions.CustomFunctionDef.ExprVisitorExn;
import edu.harvard.seas.pl.formulog.ast.functions.CustomFunctionDef.MatchClause;
import edu.harvard.seas.pl.formulog.ast.functions.CustomFunctionDef.MatchExpr;
import edu.harvard.seas.pl.formulog.ast.functions.CustomFunctionDef.TermExpr;
import edu.harvard.seas.pl.formulog.eval.EvaluationException;
import edu.harvard.seas.pl.formulog.smt.Z3ThreadFactory;
import edu.harvard.seas.pl.formulog.symbols.BuiltInConstructorSymbol;
import edu.harvard.seas.pl.formulog.symbols.BuiltInFunctionSymbol;
import edu.harvard.seas.pl.formulog.symbols.BuiltInPredicateSymbol;
import edu.harvard.seas.pl.formulog.symbols.Symbol;
import edu.harvard.seas.pl.formulog.symbols.SymbolManager;
import edu.harvard.seas.pl.formulog.symbols.FunctionSymbolForPredicateFactory.FunctionSymbolForPredicate;
import edu.harvard.seas.pl.formulog.unification.Substitution;
import edu.harvard.seas.pl.formulog.unification.Unification;
import edu.harvard.seas.pl.formulog.util.UnionFind;
import edu.harvard.seas.pl.formulog.util.Util;

public class Validator {

	private final Program prog;
	private ValidProgram vprog;
	private final Map<Symbol, Set<Rule>> rules = new HashMap<>();
	private final Map<Symbol, Set<Atom>> facts = new HashMap<>();

	public Validator(Program prog) {
		this.prog = prog;
	}

	public synchronized ValidProgram validate() throws InvalidProgramException {
		if (vprog == null) {
			validateFacts();
			validateRules();
			validateFunctionDefs();
			List<Set<Symbol>> strata = (new Stratifier(prog)).stratify();
			vprog = new ValidProgramImpl(strata);
		}
		return vprog;
	}

	private Atom normalizeFact(Atom fact, ForkJoinPool exec) throws InvalidProgramException {
		@SuppressWarnings("serial")
		Atom fact2 = exec.invoke(new RecursiveTask<Atom>() {

			@Override
			protected Atom compute() {
				try {
					return Atoms.normalize(fact);
				} catch (EvaluationException e) {
					return null;
				}
			}

		});
		if (fact2 == null) {
			throw new InvalidProgramException("Fact contains a function call that cannot be normalized: " + fact);
		}
		return fact2;
	}

	private void validateFacts() throws InvalidProgramException {
		ForkJoinPool fjp = new ForkJoinPool(1, new Z3ThreadFactory(prog.getSymbolManager()), null, true);
		for (Symbol sym : prog.getFactSymbols()) {
			Set<Atom> s = new HashSet<>();
			for (Atom fact : prog.getFacts(sym)) {
				if (!sym.getSymbolType().isEDBSymbol()) {
					throw new InvalidProgramException("Cannot define facts for non-EDB symbol " + sym);
				}
				if (!Atoms.varSet(fact).isEmpty()) {
					throw new InvalidProgramException("Every fact must be ground: " + fact);
				}
				s.add(normalizeFact(fact, fjp));
			}
			facts.put(sym, s);
		}
		fjp.shutdown();
		while (true) {
			try {
				if (fjp.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)) {
					break;
				}
			} catch (InterruptedException e) {
				// continue;
			}
		}
	}

	private void validateRules() throws InvalidProgramException {
		for (Symbol sym : prog.getRuleSymbols()) {
			Set<Rule> s = new HashSet<>();
			for (Rule r : prog.getRules(sym)) {
				try {
					r = rewriteRule(r);
					Set<Var> vars = validateHead(r);
					validateBody(r.getBody(), vars);
					s.add(simplifyRule(r));
				} catch (InvalidProgramException e) {
					throw new InvalidProgramException("Invalid rule (" + e.getMessage() + "):\n" + r);
				}
			}
			rules.put(sym, s);
		}
	}

	private static Rule simplifyRule(Rule r) {
		List<Atom> newHead = new ArrayList<>();
		for (Atom a : r.getHead()) {
			newHead.add(simplifyAtom(a));
		}
		List<Atom> newBody = new ArrayList<>();
		for (Atom a : r.getBody()) {
			newBody.add(simplifyAtom(a));
		}
		return BasicRule.get(newHead, newBody);
	}
	
	private static Atom simplifyAtom(Atom a) {
		Term[] args = a.getArgs();
		Term[] newArgs = new Term[args.length];
		for (int i = 0; i < args.length; ++i) {
			newArgs[i] = args[i].visit(termSimplifier, null);
		}
		return Atoms.get(a.getSymbol(), newArgs, a.isNegated());
	}
	
	private static TermVisitor<Void, Term> termSimplifier = new TermVisitor<Void, Term>() {

		@Override
		public Term visit(Var t, Void in) {
			return t;
		}

		@Override
		public Term visit(Constructor c, Void in) {
			Term[] args = c.getArgs();
			Term[] newArgs = new Term[args.length];
			for (int i = 0; i < args.length; ++i) {
				newArgs[i] = args[i].visit(this, in);
			}
			return c.copyWithNewArgs(newArgs);
		}

		@Override
		public Term visit(Primitive<?> p, Void in) {
			return p;
		}

		@Override
		public Term visit(FunctionCall f, Void in) {
			Symbol sym = f.getSymbol();
			if (sym.equals(BuiltInFunctionSymbol.ENTER_FORMULA) || sym.equals(BuiltInFunctionSymbol.EXIT_FORMULA)) {
				return f.getArgs()[0].visit(this, in);
			}
			Term[] args = f.getArgs();
			Term[] newArgs = new Term[args.length];
			for (int i = 0; i < args.length; ++i) {
				newArgs[i] = args[i].visit(this, in);
			}
			return f.copyWithNewArgs(newArgs);
		}
		
	};

	private static TermVisitor<List<Atom>, Term> funcRemover = new TermVisitor<List<Atom>, Term>() {

		@Override
		public Term visit(Var x, List<Atom> in) {
			return x;
		}

		@Override
		public Term visit(Constructor c, List<Atom> in) {
			Term[] args = c.getArgs();
			Term[] newArgs = new Term[args.length];
			for (int i = 0; i < args.length; ++i) {
				newArgs[i] = args[i].visit(this, in);
			}
			return c.copyWithNewArgs(newArgs);
		}

		@Override
		public Term visit(Primitive<?> p, List<Atom> in) {
			return p;
		}

		@Override
		public Term visit(FunctionCall f, List<Atom> in) {
			Term[] args = f.getArgs();
			Term[] newArgs = new Term[args.length];
			for (int i = 0; i < args.length; ++i) {
				newArgs[i] = args[i].visit(this, in);
			}
			Var x = Var.getFresh();
			Atom eq = makeUnifier(x, f.copyWithNewArgs(newArgs), false);
			in.add(eq);
			return x;
		}

	};


	private static UnifyAtom makeUnifier(Term t1, Term t2, boolean neg) {
		return (UnifyAtom) Atoms.get(BuiltInPredicateSymbol.UNIFY, new Term[] { t1, t2 }, neg);
	}

	private Atom removeFuncs(Atom a, List<Atom> acc) {
		Term[] args = a.getArgs();
		Term[] newArgs = new Term[args.length];
		for (int i = 0; i < args.length; ++i) {
			newArgs[i] = args[i].visit(funcRemover, acc);
		}
		return Atoms.get(a.getSymbol(), newArgs, a.isNegated());
	}

	// Remove function calls
	private Rule rewriteRule(Rule r) throws InvalidProgramException {
		r = removeNestedFuncs(r);
		r = simplifyUnifications(r);
		return removeVarEqualities(r);
	}

	private Rule removeNestedFuncs(Rule r) {
		List<Atom> newBody = new ArrayList<>();
		List<Atom> newHead = new ArrayList<>();
		for (Atom a : r.getBody()) {
			newBody.add(removeFuncs(a, newBody));
		}
		for (Atom a : r.getHead()) {
			newHead.add(removeFuncs(a, newBody));
		}
		return BasicRule.get(newHead, newBody);
	}

	private Rule simplifyUnifications(Rule r) throws InvalidProgramException {
		List<Atom> newBody = new ArrayList<>();
		for (Atom a : r.getBody()) {
			if (a instanceof UnifyAtom) {
				UnifyAtom u = (UnifyAtom) a;
				if (a.isNegated()) {
					simplifyNegatedUnification(u, newBody);
				} else {
					simplifyPositiveUnification(u, newBody);
				}
			} else {
				newBody.add(a);
			}
		}
		if (newBody.isEmpty()) {
			Term tru = Constructors.makeZeroAry(BuiltInConstructorSymbol.TRUE);
			newBody.add(makeUnifier(tru, tru, false));
		}
		return BasicRule.get(Util.iterableToList(r.getHead()), newBody);
	}

	private void simplifyNegatedUnification(UnifyAtom a, List<Atom> acc) {
		Term arg1 = a.getArgs()[0];
		Term arg2 = a.getArgs()[1];
		if (arg1 instanceof Constructor && arg2 instanceof Constructor) {
			Constructor c1 = (Constructor) arg1;
			Constructor c2 = (Constructor) arg2;
			Symbol sym = c1.getSymbol();
			if (!sym.equals(c2.getSymbol())) {
				return;
			}
			Term[] args1 = replaceTermsWithVars(c1.getArgs(), acc);
			Term[] args2 = replaceTermsWithVars(c2.getArgs(), acc);
			acc.add(makeUnifier(Constructors.make(sym, args1), Constructors.make(sym, args2), true));
		} else {
			acc.add(a);
		}
	}

	private Term[] replaceTermsWithVars(Term[] args, List<Atom> acc) {
		Term[] newArgs = new Term[args.length];
		for (int i = 0; i < args.length; ++i) {
			Var x = Var.getFresh();
			newArgs[i] = x;
			acc.add(makeUnifier(x, args[i], false));
		}
		return args;
	}

	private void simplifyPositiveUnification(UnifyAtom a, List<Atom> acc) throws InvalidProgramException {
		Term arg1 = a.getArgs()[0];
		Term arg2 = a.getArgs()[1];
		if (arg1 instanceof Constructor && arg2 instanceof Constructor) {
			Constructor c1 = (Constructor) arg1;
			Constructor c2 = (Constructor) arg2;
			if (!c1.getSymbol().equals(c2.getSymbol())) {
				throw new InvalidProgramException("Contains unsatisfiable unification premise: " + a);
			}
			Term[] args1 = c1.getArgs();
			Term[] args2 = c2.getArgs();
			for (int i = 0; i < args1.length; ++i) {
				simplifyPositiveUnification(makeUnifier(args1[i], args2[i], false), acc);
			}
		} else {
			acc.add(a);
		}
	}

	private Rule removeVarEqualities(Rule r) {
		List<Atom> body = Util.iterableToList(r.getBody());
		Substitution subst = removeVarEqualities(body);
		List<Atom> newHead = new ArrayList<>();
		List<Atom> newBody = new ArrayList<>();
		for (Atom a : body) {
			newBody.add(Atoms.applySubstitution(a, subst));
		}
		for (Atom a : r.getHead()) {
			newHead.add(Atoms.applySubstitution(a, subst));
		}
		return BasicRule.get(newHead, newBody);
	}

	private Substitution removeVarEqualities(List<Atom> atoms) {
		List<Atom> newAtoms = new ArrayList<>();
		UnionFind<Var> uf = new UnionFind<>();
		Substitution subst = new Substitution() {

			@Override
			public void put(Var v, Term t) {
				throw new AssertionError("impossible");
			}

			@Override
			public Term get(Var v) {
				return uf.find(v);
			}

			@Override
			public boolean containsKey(Var v) {
				return uf.contains(v);
			}

			@Override
			public Iterable<Var> iterateKeys() {
				throw new AssertionError("impossible");
			}

		};
		for (Atom a : atoms) {
			Term[] args = a.getArgs();
			if (a instanceof UnifyAtom && !a.isNegated()) {
				if (args[0] instanceof Var && args[1] instanceof Var) {
					Var x = (Var) args[0];
					Var y = (Var) args[1];
					uf.add(x);
					uf.add(y);
					uf.union(x, y);
					continue;
				}
			}
			newAtoms.add(a);
		}
		atoms.clear();
		atoms.addAll(newAtoms);
		return subst;
	}

	private Set<Var> validateHead(Rule r) throws InvalidProgramException {
		Set<Var> vars = new HashSet<>();
		for (Atom a : r.getHead()) {
			Symbol sym = a.getSymbol();
			if (!sym.getSymbolType().isIDBSymbol()) {
				throw new InvalidProgramException("Cannot define rules for non-IDB symbol " + sym);
			}
			vars.addAll(Atoms.varSet(a));
		}
		return vars;
	}

	private void validateBody(Iterable<Atom> body, Set<Var> unboundVars) throws InvalidProgramException {
		for (Atom a : body) {
			unboundVars.addAll(Atoms.varSet(a));
			validateFunctionCalls(a);
		}
		Set<Var> boundVars = new HashSet<>();
		boolean changed;
		do {
			changed = false;
			for (Atom a : body) {
				if (Unification.canBindVars(a, boundVars)) {
					changed |= boundVars.addAll(Atoms.varSet(a));
				}
			}
		} while (changed);
		unboundVars.removeAll(boundVars);
		if (!unboundVars.isEmpty()) {
			StringBuilder sb = new StringBuilder("There are unbound variables: ");
			for (Iterator<Var> it = unboundVars.iterator(); it.hasNext();) {
				sb.append("\"" + it.next() + "\"");
				if (it.hasNext()) {
					sb.append(", ");
				}
			}
			throw new InvalidProgramException(sb.toString());
		}
	}

	private void validateFunctionCalls(Atom a) throws InvalidProgramException {
		for (Term t : a.getArgs()) {
			validateFunctionCalls(t);
		}
	}

	private void validateFunctionCalls(Term t) throws InvalidProgramException {
		t.visit(new TermVisitorExn<Void, Void, InvalidProgramException>() {

			@Override
			public Void visit(Var t, Void in) throws InvalidProgramException {
				return null;
			}

			@Override
			public Void visit(Constructor t, Void in) throws InvalidProgramException {
				for (Term tt : t.getArgs()) {
					tt.visit(this, in);
				}
				return null;
			}

			@Override
			public Void visit(Primitive<?> prim, Void in) throws InvalidProgramException {
				return null;
			}

			@Override
			public Void visit(FunctionCall function, Void in) throws InvalidProgramException {
				if (!prog.getFunctionSymbols().contains(function.getSymbol())
						&& !(function.getSymbol() instanceof FunctionSymbolForPredicate)) {
					throw new InvalidProgramException(
							"Call to declared but undefined function: " + function.getSymbol());
				}
				for (Term tt : function.getArgs()) {
					tt.visit(this, in);
				}
				return null;
			}

		}, null);
	}

	private void validateFunctionDefs() throws InvalidProgramException {
		for (Symbol sym : prog.getFunctionSymbols()) {
			FunctionDef def = prog.getDef(sym);
			if (def instanceof CustomFunctionDef) {
				CustomFunctionDef cdef = (CustomFunctionDef) def;
				try {
					Set<Var> boundVars = validateFunctionParams(cdef.getParams());
					validateFunctionBody(cdef.getBody(), boundVars);
				} catch (InvalidProgramException e) {
					throw new InvalidProgramException("Invalid function definition (" + e.getMessage() + "): " + sym);
				}
			}
		}
	}

	private Set<Var> validateFunctionParams(Var[] params) throws InvalidProgramException {
		Set<Var> vars = new HashSet<>();
		for (Var param : params) {
			if (!vars.add(param)) {
				throw new InvalidProgramException(
						"Cannout use the same variable multiple times in the argument to a function definition");
			}
		}
		return vars;
	}

	private void validateFunctionBody(Expr body, Set<Var> boundVars) throws InvalidProgramException {
		body.visit(new ExprVisitorExn<Set<Var>, Void, InvalidProgramException>() {

			@Override
			public Void visit(TermExpr termExpr, Set<Var> in) throws InvalidProgramException {
				Term t = termExpr.getTerm();
				validateFunctionCalls(t);
				Set<Var> vars = Terms.varSet(t);
				vars.removeAll(in);
				if (!vars.isEmpty()) {
					StringBuilder sb = new StringBuilder();
					for (Iterator<Var> it = vars.iterator(); it.hasNext();) {
						sb.append("\"" + it.next() + "\"");
						if (it.hasNext()) {
							sb.append(", ");
						}
					}
					throw new InvalidProgramException("Unbound variables: " + sb.toString());
				}
				return null;
			}

			@Override
			public Void visit(MatchExpr matchExpr, Set<Var> in) throws InvalidProgramException {
				matchExpr.getExpr().visit(this, in);
				for (MatchClause m : matchExpr.getClauses()) {
					Term lhs = m.getLHS();
					if (lhs.containsFunctionCall()) {
						throw new InvalidProgramException(
								"Cannot have a match pattern with a term with a function invocation: " + lhs);
					}
					Set<Var> newBoundVars = new HashSet<>(in);
					newBoundVars.addAll(Terms.varSet(lhs));
					m.getRHS().visit(this, newBoundVars);
				}
				return null;
			}

		}, boundVars);
	}

	private class ValidProgramImpl implements ValidProgram {

		private final Map<Symbol, Integer> ranks;
		private final List<Set<Symbol>> strata;

		private ValidProgramImpl(List<Set<Symbol>> strata) {
			this.strata = strata;
			ranks = new HashMap<>();
			int i = 0;
			for (Set<Symbol> stratum : strata) {
				for (Symbol sym : stratum) {
					assert !ranks.containsKey(sym);
					ranks.put(sym, i);
				}
				++i;
			}
		}

		@Override
		public Set<Symbol> getFunctionSymbols() {
			return prog.getFunctionSymbols();
		}

		@Override
		public Set<Symbol> getFactSymbols() {
			return facts.keySet();
		}

		@Override
		public Set<Symbol> getRuleSymbols() {
			return prog.getRuleSymbols();
		}

		@Override
		public FunctionDef getDef(Symbol sym) {
			return prog.getDef(sym);
		}

		@Override
		public Set<Atom> getFacts(Symbol sym) {
			assert sym.getSymbolType().isEDBSymbol();
			return facts.get(sym);
		}

		@Override
		public Set<Rule> getRules(Symbol sym) {
			assert sym.getSymbolType().isIDBSymbol();
			return rules.get(sym);
		}

		@Override
		public int getStratumRank(Symbol sym) {
			if (sym.getSymbolType().isEDBSymbol()) {
				throw new IllegalArgumentException("EDB predicates (such as " + sym + ") do not belong to a stratum");
			}
			return ranks.get(sym);
		}

		@Override
		public Set<Symbol> getStratum(int rank) {
			if (rank < 0 || rank >= strata.size()) {
				throw new IllegalArgumentException(
						"Rank of " + rank + " out of required range of [0, " + strata.size() + ")");
			}
			return Collections.unmodifiableSet(strata.get(rank));
		}

		@Override
		public int getNumberOfStrata() {
			return strata.size();
		}

		@Override
		public SymbolManager getSymbolManager() {
			return prog.getSymbolManager();
		}

	}
}