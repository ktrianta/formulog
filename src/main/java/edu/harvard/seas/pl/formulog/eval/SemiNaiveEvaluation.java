package edu.harvard.seas.pl.formulog.eval;

import java.util.ArrayDeque;

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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.BiFunction;

import edu.harvard.seas.pl.formulog.Configuration;
import edu.harvard.seas.pl.formulog.ast.BasicProgram;
import edu.harvard.seas.pl.formulog.ast.BasicRule;
import edu.harvard.seas.pl.formulog.ast.ComplexLiteral;
import edu.harvard.seas.pl.formulog.ast.ComplexLiterals.ComplexLiteralVisitor;
import edu.harvard.seas.pl.formulog.ast.Term;
import edu.harvard.seas.pl.formulog.ast.Terms;
import edu.harvard.seas.pl.formulog.ast.UnificationPredicate;
import edu.harvard.seas.pl.formulog.ast.UserPredicate;
import edu.harvard.seas.pl.formulog.ast.Var;
import edu.harvard.seas.pl.formulog.db.IndexedFactDb;
import edu.harvard.seas.pl.formulog.db.IndexedFactDbBuilder;
import edu.harvard.seas.pl.formulog.db.SortedIndexedFactDb;
import edu.harvard.seas.pl.formulog.db.SortedIndexedFactDb.SortedIndexedFactDbBuilder;
import edu.harvard.seas.pl.formulog.db.View;
import edu.harvard.seas.pl.formulog.eval.SemiNaiveRule.DeltaSymbol;
import edu.harvard.seas.pl.formulog.magic.MagicSetTransformer;
import edu.harvard.seas.pl.formulog.smt.Z3ThreadFactory;
import edu.harvard.seas.pl.formulog.symbols.RelationSymbol;
import edu.harvard.seas.pl.formulog.types.WellTypedProgram;
import edu.harvard.seas.pl.formulog.unification.OverwriteSubstitution;
import edu.harvard.seas.pl.formulog.unification.SimpleSubstitution;
import edu.harvard.seas.pl.formulog.util.AbstractFJPTask;
import edu.harvard.seas.pl.formulog.util.CountingFJP;
import edu.harvard.seas.pl.formulog.util.CountingFJPImpl;
import edu.harvard.seas.pl.formulog.util.MockCountingFJP;
import edu.harvard.seas.pl.formulog.util.Pair;
import edu.harvard.seas.pl.formulog.validating.FunctionDefValidation;
import edu.harvard.seas.pl.formulog.validating.InvalidProgramException;
import edu.harvard.seas.pl.formulog.validating.Stratifier;
import edu.harvard.seas.pl.formulog.validating.Stratum;
import edu.harvard.seas.pl.formulog.validating.ValidRule;
import edu.harvard.seas.pl.formulog.validating.ast.Assignment;
import edu.harvard.seas.pl.formulog.validating.ast.BindingType;
import edu.harvard.seas.pl.formulog.validating.ast.Check;
import edu.harvard.seas.pl.formulog.validating.ast.Destructor;
import edu.harvard.seas.pl.formulog.validating.ast.SimpleLiteral;
import edu.harvard.seas.pl.formulog.validating.ast.SimpleLiteralVisitor;
import edu.harvard.seas.pl.formulog.validating.ast.SimplePredicate;
import edu.harvard.seas.pl.formulog.validating.ast.SimpleRule;

public class SemiNaiveEvaluation implements Evaluation {

	private final SortedIndexedFactDb db;
	private SortedIndexedFactDb deltaDb;
	private SortedIndexedFactDb nextDeltaDb;
	private final Map<RelationSymbol, Iterable<IndexedRule>> firstRoundRules = new HashMap<>();
	private final Map<RelationSymbol, Iterable<IndexedRule>> laterRoundRules = new HashMap<>();
	private final List<Stratum> strata;
	private final UserPredicate query;
	private volatile boolean changed;
	private final CountingFJP exec;
	private final Map<IndexedRule, boolean[]> splitPositions = new HashMap<>();

	private static final boolean sequential = System.getProperty("sequential") != null;

	public static SemiNaiveEvaluation setup(WellTypedProgram prog, int parallelism) throws InvalidProgramException {
		FunctionDefValidation.validate(prog);
		MagicSetTransformer mst = new MagicSetTransformer(prog);
		BasicProgram magicProg = mst.transform(true, true);

		Set<RelationSymbol> allRelations = new HashSet<>(magicProg.getFactSymbols());
		allRelations.addAll(magicProg.getRuleSymbols());
		SortedIndexedFactDbBuilder dbb = new SortedIndexedFactDbBuilder(allRelations);
		SortedIndexedFactDbBuilder deltaDbb = new SortedIndexedFactDbBuilder(magicProg.getRuleSymbols());
		IndexedFactDbWrapper wrappedDb = new IndexedFactDbWrapper();
		PredicateFunctionSetter predFuncs = new PredicateFunctionSetter(
				magicProg.getFunctionCallFactory().getDefManager(), magicProg.getSymbolManager(), wrappedDb);
		Map<RelationSymbol, Iterable<IndexedRule>> rules = new HashMap<>();
		List<Stratum> strata = new Stratifier(magicProg).stratify();
		for (Stratum stratum : strata) {
			if (stratum.hasRecursiveNegationOrAggregation()) {
				throw new InvalidProgramException("Cannot handle recursive negation or aggregation");
			}
			Set<RelationSymbol> stratumSymbols = stratum.getPredicateSyms();
			for (RelationSymbol sym : stratumSymbols) {
				List<IndexedRule> rs = new ArrayList<>();
				for (BasicRule br : magicProg.getRules(sym)) {
					for (SemiNaiveRule snr : SemiNaiveRule.make(br, stratumSymbols)) {
						BiFunction<ComplexLiteral, Set<Var>, Integer> score = chooseScoringFunction();
						ValidRule vr = ValidRule.make(snr, score);
						predFuncs.preprocess(vr);
						SimpleRule sr = SimpleRule.make(vr);
						IndexedRule ir = IndexedRule.make(sr, p -> {
							RelationSymbol psym = p.getSymbol();
							if (psym instanceof DeltaSymbol) {
								psym = ((DeltaSymbol) psym).getBaseSymbol();
								return deltaDbb.makeIndex(psym, p.getBindingPattern());
							} else {
								return dbb.makeIndex(psym, p.getBindingPattern());
							}
						});
						rs.add(ir);
					}
				}
				rules.put(sym, rs);
			}
		}
		SortedIndexedFactDb db = dbb.build();
		wrappedDb.set(db);

		for (RelationSymbol sym : magicProg.getFactSymbols()) {
			for (Term[] args : magicProg.getFacts(sym)) {
				try {
					db.add(sym, Terms.normalize(args, new SimpleSubstitution()));
				} catch (EvaluationException e) {
					UserPredicate p = UserPredicate.make(sym, args, false);
					throw new InvalidProgramException("Cannot normalize fact " + p + "\n" + e.getMessage());
				}
			}
		}
		db.synchronize();
		CountingFJP exec;
		if (sequential) {
			exec = new MockCountingFJP();
		} else {
			exec = new CountingFJPImpl(parallelism, new Z3ThreadFactory(magicProg.getSymbolManager()));
		}
		return new SemiNaiveEvaluation(db, deltaDbb, rules, magicProg.getQuery(), strata, exec);
	}

	private static BiFunction<ComplexLiteral, Set<Var>, Integer> chooseScoringFunction() {
		switch (Configuration.optimizationSetting) {
		case 0:
			return SemiNaiveEvaluation::score;
		case 1:
			return SemiNaiveEvaluation::score2;
		default:
			throw new IllegalArgumentException(
					"Unrecognized optimization setting: " + Configuration.optimizationSetting);
		}
	}

	private static int score(ComplexLiteral l, Set<Var> boundVars) {
		return 0;
	}

	private static int score2(ComplexLiteral l, Set<Var> boundVars) {
		// This seems to be worse than just doing nothing.
		return l.accept(new ComplexLiteralVisitor<Void, Integer>() {

			@Override
			public Integer visit(UnificationPredicate unificationPredicate, Void input) {
				return Integer.MAX_VALUE;
			}

			@Override
			public Integer visit(UserPredicate pred, Void input) {
				if (pred.isNegated()) {
					return Integer.MAX_VALUE;
				}
				if (pred.getSymbol() instanceof DeltaSymbol) {
					return 100;
				}
				Term[] args = pred.getArgs();
				if (args.length == 0) {
					return 150;
				}
				int bound = 0;
				for (int i = 0; i < args.length; ++i) {
					if (boundVars.containsAll(args[i].varSet())) {
						bound++;
					}
				}
				double percentBound = ((double) bound) / args.length * 100;
				return (int) percentBound;
			}

		}, null);
	}

	private SemiNaiveEvaluation(SortedIndexedFactDb db, IndexedFactDbBuilder<SortedIndexedFactDb> deltaDbb,
			Map<RelationSymbol, Iterable<IndexedRule>> rules, UserPredicate query, List<Stratum> strata,
			CountingFJP exec) {
		this.db = db;
		this.query = query;
		this.strata = strata;
		this.exec = exec;
		deltaDb = deltaDbb.build();
		nextDeltaDb = deltaDbb.build();
		processRules(rules);
	}

	private void processRules(Map<RelationSymbol, Iterable<IndexedRule>> rules) {
		SmtCallFinder scf = new SmtCallFinder();
		for (RelationSymbol sym : rules.keySet()) {
			Set<IndexedRule> firstRounders = new HashSet<>();
			Set<IndexedRule> laterRounders = new HashSet<>();
			for (IndexedRule rule : rules.get(sym)) {
				if (hasDelta(rule)) {
					laterRounders.add(rule);
				} else {
					firstRounders.add(rule);
				}
				boolean[] positions = findSplitPositions(rule, scf);
				splitPositions.put(rule, positions);
			}
			firstRoundRules.put(sym, firstRounders);
			laterRoundRules.put(sym, laterRounders);
		}
	}

	private static boolean[] findSplitPositions(IndexedRule rule, SmtCallFinder scf) {
		int len = rule.getBodySize();
		boolean[] splitPositions = new boolean[len];
		boolean smtCallComing = scf.containsSmtCall(rule.getHead());
		for (int i = len - 1; i >= 0; --i) {
			SimpleLiteral l = rule.getBody(i);
			if (smtCallComing && l instanceof SimplePredicate && !((SimplePredicate) l).isNegated()) {
				splitPositions[i] = true;
				smtCallComing = scf.containsSmtCall(l);
			} else {
				smtCallComing = smtCallComing || scf.containsSmtCall(l);
			}
		}
		return splitPositions;
	}

	private boolean hasDelta(IndexedRule rule) {
		boolean hasDelta = false;
		for (SimpleLiteral l : rule) {
			hasDelta |= l.accept(new SimpleLiteralVisitor<Void, Boolean>() {

				@Override
				public Boolean visit(Assignment assignment, Void input) {
					return false;
				}

				@Override
				public Boolean visit(Check check, Void input) {
					return false;
				}

				@Override
				public Boolean visit(Destructor destructor, Void input) {
					return false;
				}

				@Override
				public Boolean visit(SimplePredicate predicate, Void input) {
					return predicate.getSymbol() instanceof DeltaSymbol;
				}

			}, null);
		}
		return hasDelta;
	}

	@Override
	public synchronized void run() throws EvaluationException {
		for (Stratum stratum : strata) {
			evaluateStratum(stratum);
		}
		db.shutdown();
		deltaDb.shutdown();
		nextDeltaDb.shutdown();
	}

	private void evaluateStratum(Stratum stratum) throws EvaluationException {
		Set<RelationSymbol> syms = stratum.getPredicateSyms();
		for (RelationSymbol sym : syms) {
			for (IndexedRule r : firstRoundRules.get(sym)) {
				exec.externallyAddTask(new RulePrefixEvaluator(r));
			}
		}
		exec.blockUntilFinished();
		if (exec.hasFailed()) {
			throw exec.getFailureCause();
		}
		updateDbs();
		// int round = 1;
		while (changed) {
			// if (round % 100 == 0) {
			// System.err.println("STRATUM " + stratum.getRank() + " ROUND " + round);
			// for (RelationSymbol sym : db.getSymbols()) {
			// Set<?> s = db.getAll(sym);
			// System.err.println(sym + ": " + s.size());
			// }
			// } else {
			// System.err.println("ROUND " + round);
			// }
			changed = false;
			for (RelationSymbol sym : syms) {
				for (IndexedRule r : laterRoundRules.get(sym)) {
					exec.externallyAddTask(new RulePrefixEvaluator(r));
				}
			}
			exec.blockUntilFinished();
			if (exec.hasFailed()) {
				throw exec.getFailureCause();
			}
			updateDbs();
			// round++;
		}
	}

	private void updateDbs() {
		nextDeltaDb.synchronize();
		for (RelationSymbol sym : nextDeltaDb.getSymbols()) {
			SortedSet<Term[]> answers = nextDeltaDb.getAll(sym);
			// exec.externallyAddTask(new DbFactSplitter(sym, answers.spliterator()));
			exec.externallyAddTask(new DbFactPutter(sym, answers));
		}
		exec.blockUntilFinished();
		db.synchronize();
		SortedIndexedFactDb tmp = deltaDb;
		deltaDb = nextDeltaDb;
		nextDeltaDb = tmp;
		nextDeltaDb.clear();
	}

	private void reportFact(SimplePredicate atom) {
		RelationSymbol sym = atom.getSymbol();
		Term[] args = atom.getArgs();
		if (!db.hasFact(sym, args)) {
			nextDeltaDb.add(sym, args);
			changed = true;
		}
	}

	private View lookup(IndexedRule r, int pos, OverwriteSubstitution s) throws EvaluationException {
		SimplePredicate predicate = (SimplePredicate) r.getBody(pos);
		int idx = r.getDBIndex(pos);
		Term[] args = predicate.getArgs();
		Term[] key = new Term[args.length];
		BindingType[] pat = predicate.getBindingPattern();
		for (int i = 0; i < args.length; ++i) {
			if (pat[i].isBound()) {
				key[i] = args[i].normalize(s);
			} else {
				key[i] = args[i];
			}
		}
		if (predicate.getSymbol() instanceof DeltaSymbol) {
			return deltaDb.get(key, idx);
		} else {
			return db.get(key, idx);
		}
	}

	// @SuppressWarnings("serial")
	// private class DbFactSplitter extends AbstractFJPTask {
	//
	// private final RelationSymbol sym;
	// private final Spliterator<Term[]> split;
	//
	// public DbFactSplitter(RelationSymbol sym, Spliterator<Term[]> split) {
	// super(exec);
	// this.sym = sym;
	// this.split = split;
	// }
	//
	// @Override
	// public void doTask() throws EvaluationException {
	// while (split.estimateSize() > minTaskSize * 2) {
	// Spliterator<Term[]> split2 = split.trySplit();
	// if (split2 == null) {
	// break;
	// }
	// exec.recursivelyAddTask(new DbFactPutter(sym, split2));
	// }
	// exec.recursivelyAddTask(new DbFactPutter(sym, split));
	// }
	//
	// }

	@SuppressWarnings("serial")
	private class DbFactPutter extends AbstractFJPTask {

		private final RelationSymbol sym;
		private final Set<Term[]> tups;

		public DbFactPutter(RelationSymbol sym, Set<Term[]> tups) {
			super(exec);
			this.sym = sym;
			this.tups = tups;
		}

		@Override
		public void doTask() throws EvaluationException {
			db.addAll(sym, tups);
		}

	}

	private static final int taskSize = Configuration.taskSize;
	private static final int smtTaskSize = Configuration.smtTaskSize;
	private static final boolean recordRuleDiagnostics = Configuration.recordRuleDiagnostics;

	@SuppressWarnings("serial")
	private class RuleSuffixEvaluator extends AbstractFJPTask {

		private final IndexedRule rule;
		private final int startPos;
		private final OverwriteSubstitution s;
		private final View tups;
		private final boolean[] splitPositions;

		protected RuleSuffixEvaluator(IndexedRule rule, int pos, OverwriteSubstitution s, View tups) {
			super(exec);
			this.rule = rule;
			this.startPos = pos;
			this.s = s;
			this.tups = tups;
			this.splitPositions = SemiNaiveEvaluation.this.splitPositions.get(rule);
		}

		@Override
		public void doTask() throws EvaluationException {
			long start = 0;
			if (recordRuleDiagnostics) {
				start = System.currentTimeMillis();
			}
			boolean shouldSplit = splitPositions[startPos];
			View tups = this.tups;
			int targetSize = shouldSplit ? smtTaskSize : taskSize;
			while (tups.size() >= targetSize * 2) {
				Pair<View, View> p = tups.split();
				tups = p.fst();
				exec.recursivelyAddTask(new RuleSuffixEvaluator(rule, startPos, s.copy(), p.snd()));
			}
			try {
				for (Term[] tup : tups) {
					evaluate(tup);
				}
			} catch (UncheckedEvaluationException e) {
				throw new EvaluationException(e.getMessage());
			}
			if (recordRuleDiagnostics) {
				long end = System.currentTimeMillis();
				Configuration.recordRuleTime(rule, end - start);
			}
		}

		private void evaluate(Term[] ans) throws UncheckedEvaluationException {
			SimplePredicate p = (SimplePredicate) rule.getBody(startPos);
			updateBinding(p, ans);
			int pos = startPos + 1;
			Deque<Iterator<Term[]>> stack = new ArrayDeque<>();
			boolean movingRight = true;
			while (pos > startPos) {
				if (pos == rule.getBodySize()) {
					try {
						reportFact(rule.getHead().normalize(s));
					} catch (EvaluationException e) {
						throw new UncheckedEvaluationException("Exception raised while evaluating the literal: "
								+ rule.getHead() + "\n\n" + e.getMessage());
					}
					pos--;
					movingRight = false;
				} else if (movingRight) {
					SimpleLiteral l = rule.getBody(pos);
					try {
						switch (l.getTag()) {
						case ASSIGNMENT:
							((Assignment) l).assign(s);
							stack.addFirst(Collections.emptyIterator());
							pos++;
							break;
						case CHECK:
							if (((Check) l).check(s)) {
								stack.addFirst(Collections.emptyIterator());
								pos++;
							} else {
								pos--;
								movingRight = false;
							}
							break;
						case DESTRUCTOR:
							if (((Destructor) l).destruct(s)) {
								stack.addFirst(Collections.emptyIterator());
								pos++;
							} else {
								pos--;
								movingRight = false;
							}
							break;
						case PREDICATE:
							View answers = lookup(rule, pos, s);
							if (((SimplePredicate) l).isNegated()) {
								if (answers.isEmpty()) {
									stack.addFirst(Collections.emptyIterator());
									pos++;
								} else {
									pos--;
									movingRight = false;
								}
							} else {
								int targetSize = splitPositions[pos] ? smtTaskSize : taskSize;
								if (answers.size() >= targetSize * 2) {
									exec.recursivelyAddTask(new RuleSuffixEvaluator(rule, pos, s.copy(), answers));
									pos--;
								} else {
									stack.addFirst(answers.iterator());
									// No need to do anything else: we'll hit the right case on the next iteration.
								}
								movingRight = false;
							}
							break;
						}
					} catch (EvaluationException e) {
						throw new UncheckedEvaluationException(
								"Exception raised while evaluating the literal: " + l + "\n\n" + e.getMessage());
					}
				} else {
					Iterator<Term[]> it = stack.getFirst();
					if (it.hasNext()) {
						ans = it.next();
						updateBinding((SimplePredicate) rule.getBody(pos), ans);
						movingRight = true;
						pos++;
					} else {
						stack.removeFirst();
						pos--;
					}
				}
			}
		}

		private void updateBinding(SimplePredicate p, Term[] ans) {
			Term[] args = p.getArgs();
			BindingType[] pat = p.getBindingPattern();
			for (int i = 0; i < pat.length; ++i) {
				if (pat[i].isFree()) {
					s.put((Var) args[i], ans[i]);
				}
			}
		}

	}

	@SuppressWarnings("serial")
	private class RulePrefixEvaluator extends AbstractFJPTask {

		private final IndexedRule rule;

		protected RulePrefixEvaluator(IndexedRule rule) {
			super(exec);
			this.rule = rule;
		}

		@Override
		public void doTask() throws EvaluationException {
			long start = 0;
			if (recordRuleDiagnostics) {
				start = System.currentTimeMillis();
			}
			try {
				int len = rule.getBodySize();
				int pos = 0;
				OverwriteSubstitution s = new OverwriteSubstitution();
				loop: for (; pos < len; ++pos) {
					SimpleLiteral l = rule.getBody(pos);
					try {
						switch (l.getTag()) {
						case ASSIGNMENT:
							((Assignment) l).assign(s);
							break;
						case CHECK:
							if (!((Check) l).check(s)) {
								return;
							}
							break;
						case DESTRUCTOR:
							if (!((Destructor) l).destruct(s)) {
								return;
							}
							break;
						case PREDICATE:
							SimplePredicate p = (SimplePredicate) l;
							if (p.isNegated()) {
								if (!lookup(rule, pos, s).isEmpty()) {
									return;
								}
							} else {
								// Stop on the first positive user predicate.
								break loop;
							}
							break;
						}
					} catch (EvaluationException e) {
						throw new EvaluationException(
								"Exception raised while evaluating the literal: " + l + "\n\n" + e.getMessage());
					}
				}
				if (pos == len) {
					try {
						reportFact(rule.getHead().normalize(s));
						return;
					} catch (EvaluationException e) {
						throw new EvaluationException("Exception raised while evaluationg the literal: "
								+ rule.getHead() + e.getLocalizedMessage());
					}
				}
				exec.recursivelyAddTask(new RuleSuffixEvaluator(rule, pos, s, lookup(rule, pos, s)));
			} catch (EvaluationException e) {
				throw new EvaluationException(
						"Exception raised while evaluating this rule:\n" + rule + "\n\n" + e.getMessage());
			}
			if (recordRuleDiagnostics) {
				long end = System.currentTimeMillis();
				Configuration.recordRuleTime(rule, end - start);
			}
		}

	}

	@Override
	public synchronized EvaluationResult getResult() {
		return new EvaluationResult() {

			@Override
			public Iterable<UserPredicate> getAll(RelationSymbol sym) {
				if (!db.getSymbols().contains(sym)) {
					throw new IllegalArgumentException("Unrecognized relation symbol " + sym);
				}
				return new Iterable<UserPredicate>() {

					@Override
					public Iterator<UserPredicate> iterator() {
						return new FactIterator(sym, db.getAll(sym).iterator());
					}

				};
			}

			@Override
			public Iterable<UserPredicate> getQueryAnswer() {
				if (query == null) {
					return null;
				}
				RelationSymbol querySym = query.getSymbol();
				return new Iterable<UserPredicate>() {

					@Override
					public Iterator<UserPredicate> iterator() {
						return new FactIterator(querySym, db.getAll(querySym).iterator());
					}

				};
			}

			@Override
			public Set<RelationSymbol> getSymbols() {
				return Collections.unmodifiableSet(db.getSymbols());
			}

		};
	}

	private static class FactIterator implements Iterator<UserPredicate> {

		private final RelationSymbol sym;
		private final Iterator<Term[]> bindings;

		public FactIterator(RelationSymbol sym, Iterator<Term[]> bindings) {
			this.sym = sym;
			this.bindings = bindings;
		}

		@Override
		public boolean hasNext() {
			return bindings.hasNext();
		}

		@Override
		public UserPredicate next() {
			return UserPredicate.make(sym, bindings.next(), false);
		}

	}

	@Override
	public boolean hasQuery() {
		return query != null;
	}

	@Override
	public UserPredicate getQuery() {
		return query;
	}

	private static class IndexedFactDbWrapper implements IndexedFactDb {

		private IndexedFactDb db;

		public void set(IndexedFactDb db) {
			this.db = db;
		}

		@Override
		public Set<RelationSymbol> getSymbols() {
			return db.getSymbols();
		}

		@Override
		public Set<Term[]> getAll(RelationSymbol sym) {
			return db.getAll(sym);
		}

		@Override
		public View get(Term[] key, int index) {
			return db.get(key, index);
		}

		@Override
		public boolean add(RelationSymbol sym, Term[] args) {
			return db.add(sym, args);
		}

		@Override
		public boolean hasFact(RelationSymbol sym, Term[] args) {
			return db.hasFact(sym, args);
		}

		@Override
		public void clear() {
			db.clear();
		}

		@Override
		public boolean addAll(RelationSymbol sym, Set<Term[]> tups) {
			return db.addAll(sym, tups);
		}

	}

}
