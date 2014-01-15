package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.statemachine.MachineState;

public class ForwardDeadReckonInternalMachineState implements Iterable<ForwardDeadReckonPropositionCrossReferenceInfo>
{
	private class InternalMachineStateIterator implements Iterator<ForwardDeadReckonPropositionCrossReferenceInfo>
	{
		private ForwardDeadReckonInternalMachineState parent;
		int	index;
		
		public InternalMachineStateIterator(ForwardDeadReckonInternalMachineState parent)
		{
			this.parent = parent;
			index = parent.contents.nextSetBit(0);
		}
		
		@Override
		public boolean hasNext() {
			return (index != -1);
		}

		@Override
		public ForwardDeadReckonPropositionCrossReferenceInfo next() {
			ForwardDeadReckonPropositionCrossReferenceInfo result = parent.infoSet[index];
			index = parent.contents.nextSetBit(index+1);
			return result;
		}

		@Override
		public void remove() {
			// TODO Auto-generated method stub
			
		}
	}
	
	private ForwardDeadReckonPropositionCrossReferenceInfo[] infoSet;
	
	BitSet contents = new BitSet();
	//Set<ForwardDeadReckonPropositionCrossReferenceInfo> contents = new HashSet<ForwardDeadReckonPropositionCrossReferenceInfo>();
	public boolean isXState = false;
	
	public ForwardDeadReckonInternalMachineState(ForwardDeadReckonPropositionCrossReferenceInfo[] infoSet) {
		this.infoSet = infoSet;
	}
	
	public ForwardDeadReckonInternalMachineState(ForwardDeadReckonInternalMachineState copyFrom) {
		this.infoSet = copyFrom.infoSet;
		copy(copyFrom);
	}

	public void add(ForwardDeadReckonPropositionCrossReferenceInfo info)
	{
		contents.set(info.index);
	}
	
	public boolean contains(ForwardDeadReckonPropositionCrossReferenceInfo info)
	{
		return contents.get(info.index);
	}
	
	public int size()
	{
		return contents.cardinality();
	}
	
	public void xor(ForwardDeadReckonInternalMachineState other)
	{
		contents.xor(other.contents);
	}
	
	public void merge(ForwardDeadReckonInternalMachineState other)
	{
		contents.or(other.contents);
	}
	
	public void intersect(ForwardDeadReckonInternalMachineState other)
	{
		contents.and(other.contents);
	}
	
	public int intersectionSize(ForwardDeadReckonInternalMachineState other)
	{
		ForwardDeadReckonInternalMachineState temp = new ForwardDeadReckonInternalMachineState(other);
		
		temp.intersect(this);
		
		return temp.contents.cardinality();
	}
	
	public void copy(ForwardDeadReckonInternalMachineState other)
	{
		contents.clear();
		contents.or(other.contents);
		
		isXState = other.isXState;
	}
	
	public double distance(ForwardDeadReckonInternalMachineState other)
	{
		ForwardDeadReckonInternalMachineState temp = new ForwardDeadReckonInternalMachineState(other);
		
		temp.xor(this);
		int diff = temp.contents.cardinality();
		temp.copy(other);
		temp.merge(this);
		int jointSize = temp.contents.cardinality();
		
		return (double)diff/jointSize;
	}
	
	public void clear()
	{
		contents.clear();
	}
	
	public void remove(ForwardDeadReckonPropositionCrossReferenceInfo info)
	{
		contents.clear(info.index);
	}
	
	public MachineState getMachineState()
	{
		//ProfileSection methodSection = new ProfileSection("InternalMachineState.getMachineState");
		//try
		{
			MachineState result = new MachineState(new HashSet<GdlSentence>());
			
			for (int i = contents.nextSetBit(0); i >= 0; i = contents.nextSetBit(i+1))
			{
				result.getContents().add(infoSet[i].sentence);
			}
			
			return result;
		}
		//finally
		//{
		//	methodSection.exitScope();
		//}
	}

	/* Utility methods */
    public int hashCode()
    {
        return contents.hashCode();
    }

    public boolean equals(Object o)
    {
    	if ( this == o )
    	{
    		return true;
    	}
    	
        if ((o != null) && (o instanceof ForwardDeadReckonInternalMachineState))
        {
        	ForwardDeadReckonInternalMachineState state = (ForwardDeadReckonInternalMachineState) o;
            return state.contents.equals(contents);
        }

        return false;
    }
	
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		
		sb.append("( ");
		for (int i = contents.nextSetBit(0); i >= 0; i = contents.nextSetBit(i+1))
		{
			if ( !first )
			{
				sb.append(", ");
			}
			sb.append(infoSet[i].sentence);
			first = false;
		}
		sb.append(" )");
		
		return sb.toString();
	}

	@Override
	public Iterator<ForwardDeadReckonPropositionCrossReferenceInfo> iterator() {
		// TODO Auto-generated method stub
		return new InternalMachineStateIterator(this);
	}

	public boolean contains(ForwardDeadReckonInternalMachineState other)
	{
		ForwardDeadReckonInternalMachineState temp = new ForwardDeadReckonInternalMachineState(other);
		
		temp.intersect(this);
		return other.contents.cardinality() == temp.contents.cardinality();
	}
}
