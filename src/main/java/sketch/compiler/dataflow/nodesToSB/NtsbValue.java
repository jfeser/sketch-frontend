package streamit.frontend.experimental.nodesToSB;

import java.util.Iterator;
import java.util.List;

import streamit.frontend.experimental.abstractValue;




public class NtsbValue extends IntAbsValue {
	NtsbState.lhsIndexes lhsIdx;
	private final String name; // name is only used when it is LHS.
	private int rhsIdx=0;
	
	boolean isAXPB=false;
	int A;
	int B;
	NtsbValue X;
	
	public abstractValue clone(){	
		return new NtsbValue(this);
	}
	
	public int getlhsIdx(){
		assert lhsIdx != null : "This is not legal";
		return lhsIdx.idx;
	}
	
	public int getrhsIdx(){
		return rhsIdx;
	}
	
	public NtsbValue(String name, NtsbState.lhsIndexes lhsidx){
		this.obj = name;
		this.name = name;
		this.type = BOTTOM;
		assert name != null : "This should never happen!!!! Name should never be null.";
		this.lhsIdx = lhsidx;
	}
	
	public NtsbValue(NtsbValue n){
		this.lhsIdx = n.lhsIdx;
		this.rhsIdx = n.rhsIdx;
		this.obj = n.obj;
		this.type = n.type;
		this.name = n.name;
		this.isVolatile = n.isVolatile;
	}
	
	public NtsbValue(String label){
		this.obj = label;
		this.type = BOTTOM;
		assert label != null : "This should never happen!!!! Name should never be null.";
		this.name = null;
	}
	
	public NtsbValue(){
		this.obj = null;
		this.type = BOTTOM;
		this.name = null;
	}
	public NtsbValue(Object obj, int type){
		this.obj = obj;
		this.type = type;
		this.name = null;
	}


	
	public NtsbValue(List<abstractValue> obj){
		this.obj = obj;
		this.type = LIST;
		this.name = null;
	}
	public NtsbValue(boolean obj){
		this.obj = obj? new Integer(1) : new Integer(0);
		this.type = INT;
		this.name = null;
	}
	public NtsbValue(int obj){
		this.obj = obj;
		this.type = INT;
		this.name = null;
	}
	public String toString(){
		switch(type){
		case INT: return obj.toString();
		case LIST: {
			StringBuffer rval = new StringBuffer();
			rval.append( "$ " );
			
			for(Iterator<abstractValue> it = getVectValue().iterator(); it.hasNext(); ){
				rval.append( it.next().toString() + " ");
			}
			rval.append("$");
			return rval.toString();
		}
		case BOTTOM:{ 
			if( lhsIdx != null ){
				return  name + "_" +  this.getrhsIdx();
			}else{
				if( obj != null ){
					return obj.toString();
				}	
			}
		}
		}
		return "NULL";
	}
	public void update(abstractValue v){
		assert !isVolatile : "NtsbValue does not support volatility."; 
		assert lhsIdx != null;
		NtsbValue nb = (NtsbValue)v;
		if(nb.isAXPB){
			isAXPB = true;
			X = new NtsbValue(nb.X.toString());
			A = nb.A;
			B = nb.B;
		}else{
			isAXPB=false;
			X = null;
		}		
		
		rhsIdx = lhsIdx.idx;
		lhsIdx.idx ++;
		super.update(v);
	}
}
