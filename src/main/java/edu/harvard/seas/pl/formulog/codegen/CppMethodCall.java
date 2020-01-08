package edu.harvard.seas.pl.formulog.codegen;

/*-
 * #%L
 * FormuLog
 * %%
 * Copyright (C) 2018 - 2020 President and Fellows of Harvard College
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

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

public class CppMethodCall implements CppExpr {

	private final String func;
	private final CppExpr rec;
	private final List<CppExpr> args;
	
	private CppMethodCall(CppExpr rec, String func, List<CppExpr> args) {
		this.func = func;
		this.rec = rec;
		this.args = args;
	}
	
	public static CppMethodCall mk(CppExpr rec, String func, List<CppExpr> args) {
		return new CppMethodCall(rec, func, args);
	}
	
	public static CppMethodCall mk(CppExpr rec, String func, CppExpr... args) {
		return new CppMethodCall(rec, func, Arrays.asList(args));
	}

	@Override
	public void print(PrintWriter out) {
		rec.print(out);
		out.print(".");
		out.print(func);
		out.print("(");
		CodeGenUtil.printSeparated(args, ", ", out);
		out.print(")");
	}

}
