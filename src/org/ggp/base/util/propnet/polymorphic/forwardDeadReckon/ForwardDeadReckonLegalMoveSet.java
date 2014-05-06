
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.ggp.base.util.statemachine.Role;

public class ForwardDeadReckonLegalMoveSet implements ForwardDeadReckonComponentTransitionNotifier
{
  private List<ForwardDeadReckonLegalMoveInfo> masterList;
  private ForwardDeadReckonLegalMoveInfo[]     masterListAsArray;
  private BitSet[]                             contents;
  private Role[]                               roles;

  private class ForwardDeadReckonLegalMoveSetIterator
                                                     implements
                                                     Iterator<ForwardDeadReckonLegalMoveInfo>
  {
    private ForwardDeadReckonLegalMoveSet parent;
    int                                   index;
    int                                   roleIndex;

    public ForwardDeadReckonLegalMoveSetIterator(ForwardDeadReckonLegalMoveSet parent,
                                                 int roleIndex)
    {
      this.parent = parent;
      this.roleIndex = roleIndex;
      index = parent.contents[roleIndex].nextSetBit(0);
    }

    @Override
    public boolean hasNext()
    {
      return (index != -1);
    }

    @Override
    public ForwardDeadReckonLegalMoveInfo next()
    {
      ForwardDeadReckonLegalMoveInfo result = parent.masterListAsArray[index];
      index = parent.contents[roleIndex].nextSetBit(index + 1);
      return result;
    }

    @Override
    public void remove()
    {
      // TODO Auto-generated method stub

    }
  }

  private class ForwardDeadReckonLegalMoveSetCollection
                                                       implements
                                                       Collection<ForwardDeadReckonLegalMoveInfo>
  {
    private ForwardDeadReckonLegalMoveSet parent;
    int                                   roleIndex;

    public ForwardDeadReckonLegalMoveSetCollection(ForwardDeadReckonLegalMoveSet parent,
                                                   int roleIndex)
    {
      this.parent = parent;
      this.roleIndex = roleIndex;
    }

    @Override
    public Iterator<ForwardDeadReckonLegalMoveInfo> iterator()
    {
      // TODO Auto-generated method stub
      return new ForwardDeadReckonLegalMoveSetIterator(parent, roleIndex);
    }

    @Override
    public boolean add(ForwardDeadReckonLegalMoveInfo xiE)
    {
      // Not supported - this is a read-only collection
      return false;
    }

    @Override
    public boolean addAll(Collection<? extends ForwardDeadReckonLegalMoveInfo> xiC)
    {
      // Not supported - this is a read-only collection
      return false;
    }

    @Override
    public void clear()
    {
      // Not supported - this is a read-only collection
    }

    @Override
    public boolean contains(Object xiO)
    {
      ForwardDeadReckonLegalMoveInfo move = (ForwardDeadReckonLegalMoveInfo)xiO;
      return (move != null && parent.contents[roleIndex].get(move.masterIndex));
    }

    @Override
    public boolean containsAll(Collection<?> xiC)
    {
      for(Object o : xiC)
      {
        if ( !contains(o))
        {
          return false;
        }
      }
      return true;
    }

    @Override
    public boolean isEmpty()
    {
      // TODO Auto-generated method stub
      return (parent.contents[roleIndex].cardinality() == 0);
    }

    @Override
    public boolean remove(Object xiO)
    {
      // Not supported - this is a read-only collection
      return false;
    }

    @Override
    public boolean removeAll(Collection<?> xiC)
    {
      // Not supported - this is a read-only collection
      return false;
    }

    @Override
    public boolean retainAll(Collection<?> xiC)
    {
      // Not supported - this is a read-only collection
      return false;
    }

    @Override
    public int size()
    {
      return parent.contents[roleIndex].cardinality();
    }

    @Override
    public Object[] toArray()
    {
      Object[] result = new Object[size()];
      int index = 0;

      for(Object o : this)
      {
        result[index++] = o;
      }
      return result;
    }

    @Override
    public <T> T[] toArray(T[] xiA)
    {
      // TODO Auto-generated method stub
      return null;
    }

  }

  public ForwardDeadReckonLegalMoveSet(ForwardDeadReckonLegalMoveSet master)
  {
    masterList = master.masterList;
    masterListAsArray = master.masterListAsArray;
    roles = master.roles;
    contents = new BitSet[roles.length];

    int i = 0;
    for (Role role : roles)
    {
      contents[i++] = new BitSet();
    }
  }

  public ForwardDeadReckonLegalMoveSet(List<Role> roles)
  {
    masterList = new ArrayList<ForwardDeadReckonLegalMoveInfo>();
    masterListAsArray = null;
    contents = new BitSet[roles.size()];
    this.roles = new Role[roles.size()];

    int i = 0;
    for (Role role : roles)
    {
      contents[i] = new BitSet();
      this.roles[i++] = role;
    }
  }

  public void crystalize()
  {
    masterListAsArray = new ForwardDeadReckonLegalMoveInfo[masterList.size()];
    masterList.toArray(masterListAsArray);
    masterList = null;
  }

  public ForwardDeadReckonLegalMoveInfo[] getMasterList()
  {
    return masterListAsArray;
  }

  public void clear()
  {
    for (int i = 0; i < contents.length; i++)
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

  @Override
  public void add(int index)
  {
    //assert(index >= 0 && index < 1000);
    ForwardDeadReckonLegalMoveInfo info = masterListAsArray[index];
    contents[info.roleIndex].set(index);
  }

  public void remove(ForwardDeadReckonLegalMoveInfo info)
  {
    if (info.masterIndex != -1)
    {
      contents[info.roleIndex].clear(info.masterIndex);
    }
  }

  @Override
  public void remove(int index)
  {
    //assert(index >= 0 && index < 1000);
    ForwardDeadReckonLegalMoveInfo info = masterListAsArray[index];
    contents[info.roleIndex].clear(index);
  }

  public void merge(ForwardDeadReckonLegalMoveSet other)
  {
    for (int i = 0; i < contents.length; i++)
    {
      contents[i].or(other.contents[i]);
    }
  }

  public Collection<ForwardDeadReckonLegalMoveInfo> getContents(int roleIndex)
  {
    return new ForwardDeadReckonLegalMoveSetCollection(this, roleIndex);
  }

  public Collection<ForwardDeadReckonLegalMoveInfo> getContents(Role role)
  {
    for (int i = 0; i < roles.length; i++)
    {
      if (roles[i].equals(role))
      {
        return new ForwardDeadReckonLegalMoveSetCollection(this, i);
      }
    }

    return null;
  }
}
