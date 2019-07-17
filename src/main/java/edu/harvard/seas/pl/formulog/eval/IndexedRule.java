package edu.harvard.seas.pl.formulog.eval;

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
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

import edu.harvard.seas.pl.formulog.ast.Rule;
import edu.harvard.seas.pl.formulog.db.IndexedFactDbBuilder;
import edu.harvard.seas.pl.formulog.symbols.RelationSymbol;
import edu.harvard.seas.pl.formulog.util.Util;
import edu.harvard.seas.pl.formulog.validating.ast.Assignment;
import edu.harvard.seas.pl.formulog.validating.ast.Check;
import edu.harvard.seas.pl.formulog.validating.ast.Destructor;
import edu.harvard.seas.pl.formulog.validating.ast.SimplePredicate;
import edu.harvard.seas.pl.formulog.validating.ast.SimpleConjunct;
import edu.harvard.seas.pl.formulog.validating.ast.SimpleConjunctVisitor;

public class IndexedRule implements Rule<SimplePredicate, SimpleConjunct> {

	private final SimplePredicate head;
	private final List<SimpleConjunct> body;
	private final List<Integer> idxs;

	public IndexedRule(Rule<SimplePredicate, SimpleConjunct> rule, IndexedFactDbBuilder<?> dbb,
			Predicate<RelationSymbol> makeIndex) {
		head = rule.getHead();
		body = Util.iterableToList(rule);
		idxs = createIndexes(dbb, makeIndex);
	}

	private List<Integer> createIndexes(IndexedFactDbBuilder<?> dbb, Predicate<RelationSymbol> makeIndex) {
		List<Integer> idxs = new ArrayList<>();
		for (SimpleConjunct a : body) {
			idxs.add(a.accept(new SimpleConjunctVisitor<Void, Integer>() {

				@Override
				public Integer visit(Assignment assignment, Void input) {
					return null;
				}

				@Override
				public Integer visit(Check check, Void input) {
					return null;
				}

				@Override
				public Integer visit(Destructor destructor, Void input) {
					return null;
				}

				@Override
				public Integer visit(SimplePredicate predicate, Void input) {
					if (makeIndex.test(predicate.getSymbol())) {
						dbb.makeIndex(predicate);
					}
					return null;
				}

			}, null));
		}
		return idxs;
	}

	@Override
	public SimplePredicate getHead() {
		return head;
	}

	@Override
	public int getBodySize() {
		return body.size();
	}

	@Override
	public SimpleConjunct getBody(int idx) {
		return body.get(idx);
	}

	public Integer getDBIndex(int idx) {
		return idxs.get(idx);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(head);
		sb.append(" :-");
		if (body.size() == 1) {
			sb.append(" ");
		} else {
			sb.append("\n\t");
		}
		for (Iterator<SimpleConjunct> it = body.iterator(); it.hasNext();) {
			sb.append(it.next());
			if (it.hasNext()) {
				sb.append(",\n\t");
			}
		}
		sb.append(".");
		return sb.toString();
	}

	@Override
	public Iterator<SimpleConjunct> iterator() {
		return body.iterator();
	}

}
