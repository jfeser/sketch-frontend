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

package streamit.frontend.nodes;

import java.util.ArrayList;
import java.util.List;

/**
 * A fixed-length homogenous array type.  This type has a base type and
 * an expression for the length.  The expression must be real and integral,
 * but may contain variables if they can be resolved by constant propagation.
 *
 * @author  David Maze &lt;dmaze@cag.lcs.mit.edu&gt;
 * @version $Id$
 */
public class TypeArray extends Type
{
    private Type base;
    private Expression length;
    
    /** Creates an array type of the specified base type with the
     * specified length. */
    public TypeArray(Type base, Expression length)
    {
        this.base = base;
        this.length = length;
    }
    
    /** Gets the base type of this. */
    public Type getBase()
    {
        return base;
    }
    
    /** Gets the length of this. */
    public Expression getLength()
    {
        return length;
    }

    public String toString()
    {
        return base + "[" + length + "]";
    }
    
    public boolean promotesTo(Type other)
    {
        if (super.promotesTo(other))
            return true;
        if (!(other instanceof TypeArray))
            return false;
        TypeArray that = (TypeArray)other;
        if (!(this.getBase().promotesTo(that.getBase())))
            return false;
        Expression thisLen = this.getLength();
        Expression thatLen = that.getLength();
        if(thisLen.getIValue() != null && thatLen.getIValue() != null){
        	int ithis = thisLen.getIValue();
        	int ithat = thatLen.getIValue();
        	return ithis <= ithat;
        }
//        if (!(thisLen.equals(thatLen)))
//            return false;
        return true;
    }
    
    
    public boolean equals(Object other)
    {
        if (!(other instanceof TypeArray))
            return false;
        TypeArray that = (TypeArray)other;
        if (!(this.getBase().equals(that.getBase())))
            return false;
        Expression thisLen = this.getLength();
        Expression thatLen = that.getLength();
        if(thisLen.getIValue() != null && thatLen.getIValue() != null){
        	return thisLen.getIValue().equals(thatLen.getIValue());
        }
        if (!(thisLen.equals(thatLen)))
            return false;
        return true;
    }
    
    public int hashCode()
    {
        return base.hashCode() ^ length.hashCode();
    }
    public Type makeNonDet()
    {
        return new TypeArray(base.makeNonDet() , length);
    }
    public boolean isNonDet(){
    	return base.isNonDet();
    }
    
    public List<Expression> getDimensions() {
    	List<Expression> ret=new ArrayList<Expression>();
    	Type t=this;
    	while(t instanceof TypeArray) {
    		TypeArray ta=(TypeArray) t;
    		ret.add(0,ta.getLength());
    		t=ta.getBase();
    	}
    	return ret;
    }
}
