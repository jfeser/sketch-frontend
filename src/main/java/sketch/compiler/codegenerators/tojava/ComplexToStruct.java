/*
 * Copyright 2003 by the Massachusetts Institute of Technology.
 *
 * Permission to use, copy, modify, and distribute this
 * software and its documentation for any purpose and without
 * fee is hereby granted, provided that the above copyright
 * notice appear in all copies and that both that copyright
 * notice and this permission notice appear in supporting
 * documentation, and that the name of M.I.T. not be used in
 * advertising or publicity pertaining to distribution of the
 * software without specific, written prior permission.
 * M.I.T. makes no representations about the suitability of
 * this software for any purpose.  It is provided "as is"
 * without express or implied warranty.
 */

package sketch.compiler.codegenerators.tojava;

import java.util.Iterator;
import java.util.List;

import sketch.compiler.ast.core.FEReplacer;
import sketch.compiler.ast.core.FieldDecl;
import sketch.compiler.ast.core.Function;
import sketch.compiler.ast.core.Parameter;
import sketch.compiler.ast.core.Program;
import sketch.compiler.ast.core.StreamSpec;
import sketch.compiler.ast.core.StreamType;
import sketch.compiler.ast.core.stmts.StmtVarDecl;
import sketch.compiler.ast.core.typs.Type;
import sketch.compiler.ast.core.typs.TypeArray;
import sketch.compiler.ast.core.typs.TypeStruct;

/**
 * Replace primitive complex types with the <code>Complex</code>
 * structure.  This looks through all of the structure declarations
 * and replaces types in variable and field declarations and
 * parameters with the correct actual structure type.
 *
 * @author  David Maze &lt;dmaze@cag.lcs.mit.edu&gt;
 * @version $Id$
 */
public class ComplexToStruct extends FEReplacer
{
    private TypeStruct cplxType;

    private Type remapType(Type type)
    {
        if (type.isComplex())
            return cplxType;
        if (type instanceof TypeArray)
        {
            TypeArray ta = (TypeArray)type;
            type = new TypeArray(remapType(ta.getBase()), ta.getLength());
        }
        return type;
    }

    public ComplexToStruct()
    {
        cplxType = null;
    }

    public Object visitProgram(Program prog)
    {
        // Go through the list of structures, and notice them all.
        // We also need to rewrite the structures, in case there are
        // structs that contain structs.
        List newStructs = new java.util.ArrayList();
        // Pass 1: find the struct named "Complex".
        for (Iterator iter = prog.getStructs().iterator(); iter.hasNext(); )
        {
            TypeStruct struct = (TypeStruct)iter.next();
            if (struct.getName().equals("Complex"))
                cplxType = struct;
        }
        // Pass 2: rewrite existing structures.
        for (Iterator iter = prog.getStructs().iterator(); iter.hasNext(); )
        {
            TypeStruct struct = (TypeStruct)iter.next();
            List newNames = new java.util.ArrayList();
            List newTypes = new java.util.ArrayList();
            for (int i = 0; i < struct.getNumFields(); i++)
            {
                String name = struct.getField(i);
                Type type = remapType(struct.getType(name));
                newNames.add(name);
                newTypes.add(type);
            }
            struct = new TypeStruct(struct.getContext (), struct.getName(),
                                    newNames, newTypes);
            newStructs.add(struct);
        }
        return super.visitProgram(prog.creator().structs(newStructs).create());
    }

    public Object visitFieldDecl(FieldDecl field)
    {
        List newTypes = new java.util.ArrayList();
        for (int i = 0; i < field.getNumFields(); i++)
            newTypes.add(remapType(field.getType(i)));
        return new FieldDecl(field, newTypes,
                             field.getNames(), field.getInits());
    }

    public Object visitFunction(Function func)
    {
        // Visit the parameter list, then let FEReplacer do the
        // rest of the work.
        List newParams = new java.util.ArrayList();
        for (Iterator iter = func.getParams().iterator(); iter.hasNext(); )
        {
            Parameter param = (Parameter)iter.next();
            Type type = remapType(param.getType());
            param = new Parameter(type, param.getName(), param.getPtype());
            newParams.add(param);
        }
        Type returnType = remapType(func.getReturnType());
        return super.visitFunction(func.creator().returnType(returnType).params(newParams).create());
    }

    public Object visitStmtVarDecl(StmtVarDecl stmt)
    {
        List newTypes = new java.util.ArrayList();
        for (int i = 0; i < stmt.getNumVars(); i++)
            newTypes.add(remapType(stmt.getType(i)));
        return new StmtVarDecl(stmt, newTypes,
                               stmt.getNames(), stmt.getInits());
    }

    public Object visitStreamSpec(StreamSpec ss)
    {
        // Visit the parameter list, then let FEReplacer do the
        // rest of the work.
        List newParams = new java.util.ArrayList();
        for (Iterator iter = ss.getParams().iterator(); iter.hasNext(); )
        {
            Parameter param = (Parameter)iter.next();
            Type type = remapType(param.getType());
            param = new Parameter(type, param.getName(), param.getPtype());
            newParams.add(param);
        }
        return super.visitStreamSpec(new StreamSpec(ss,
                                                    ss.getType(),
                                                    ss.getStreamType(),
                                                    ss.getName(),
                                                    newParams,
                                                    ss.getVars(),
                                                    ss.getFuncs()));
    }

    public Object visitStreamType(StreamType st)
    {
        return new StreamType(st,
                              remapType(st.getIn()),
                              remapType(st.getOut()),
                              remapType(st.getLoop()));
    }
}
