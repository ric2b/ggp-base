package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

import org.ggp.base.util.statemachine.Role;

public class ForwardDeadReckonLegalMoveSet {
	private List<ForwardDeadReckonLegalMoveInfo> masterList;
	private BitSet[] contents;
	private Role[] roles;
	
	private class ForwardDeadReckonLegalMoveSetIterator implements Iterator<ForwardDeadReckonLegalMoveInfo>
	{
		private ForwardDeadReckonLegalMoveSet parent;
		int	index;
		int roleIndex;
		
		public ForwardDeadReckonLegalMoveSetIterator(ForwardDeadReckonLegalMoveSet parent, int roleIndex)
		{
			this.parent = parent;
			this.roleIndex = roleIndex;
			index = parent.contents[roleIndex].nextSetBit(0);
		}
		
		@Override
		public boolean hasNext() {
			return (index != -1);
		}

		@Override
		public ForwardDeadReckonLegalMoveInfo next() {
			ForwardDeadReckonLegalMoveInfo result = parent.masterList.get(index);
			index = parent.contents[roleIndex].nextSetBit(index+1);
			return result;
		}

		@Override
		public void remove() {
			// TODO Auto-generated method stub
			
		}
	}
	
	private class ForwardDeadReckonLegalMoveSetIteratable implements Iterable<ForwardDeadReckonLegalMoveInfo>
	{
		private ForwardDeadReckonLegalMoveSet parent;
		int roleIndex;
		
		public ForwardDeadReckonLegalMoveSetIteratable(ForwardDeadReckonLegalMoveSet parent, int roleIndex)
		{
			this.parent = parent;
			this.roleIndex = roleIndex;
		}
		
		@Override
		public Iterator<ForwardDeadReckonLegalMoveInfo> iterator() {
			// TODO Auto-generated method stub
			return new ForwardDeadReckonLegalMoveSetIterator(parent, roleIndex);
		}
		
	}
	
	public ForwardDeadReckonLegalMoveSet(ForwardDeadReckonLegalMoveSet master)
	{
		masterList = master.masterList;
		roles = master.roles;
		contents = new BitSet[roles.length];
		
		int i = 0;
		for(Role role : roles)
		{
			contents[i++] = new BitSet();
		}
	}
		
	public ForwardDeadReckonLegalMoveSet(List<Role> roles)
	{
		masterList = new ArrayList<ForwardDeadReckonLegalMoveInfo>();
		contents = new BitSet[roles.size()];
		this.roles = new Role[roles.size()];
		
		int i = 0;
		for(Role role : roles)
		{
			contents[i] = new BitSet();
			this.roles[i++] = role;
		}
	}
	
	public void clear()
	{
		for(int i = 0; i < contents.length; i++)
		{
			contents[i].clear();
		}
	}
	
	public int resolveId(ForwardDeadReckonLegalMoveInfo info)
	{
		masterList.add(info);
		
		return masterList.size() - 1;
	}
	
	public void add(ForwardDeadReckonLegalMoveInfo info)
	{
		contents[info.roleIndex].set(info.masterIndex);
	}
	
	public void remove(ForwardDeadReckonLegalMoveInfo info)
	{
		if ( info.masterIndex != -1 )
		{
			contents[info.roleIndex].clear(info.masterIndex);
		}
	}
	
	public void merge(ForwardDeadReckonLegalMoveSet other)
	{
		for(int i = 0; i < contents.length; i++)
		{
			contents[i].or(other.contents[i]);
		}
	}
	
	public Iterable<ForwardDeadReckonLegalMoveInfo> getContents(int roleIndex)
	{
		return new ForwardDeadReckonLegalMoveSetIteratable(this, roleIndex);
	}
	
	public Iterable<ForwardDeadReckonLegalMoveInfo> getContents(Role role)
	{
		for(int i = 0; i < roles.length; i++)
		{
			if ( roles[i].equals(role) )
			{
				return new ForwardDeadReckonLegalMoveSetIteratable(this, i);
			}
		}
		
		return null;
	}
	
	public int getContentSize(Role role)
	{
		for(int i = 0; i < roles.length; i++)
		{
			if ( roles[i].equals(role) )
			{
				return contents[i].cardinality();
			}
		}
		
		return 0;
	}
}
