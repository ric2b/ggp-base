
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.apache.lucene.util.OpenBitSet;
import org.ggp.base.util.statemachine.Role;

/**
 * @author steve
 * Collection of currently legal moves associated with a propNet.  The propNet
 * will update this collection by direct notification when legal move propositions change
 * state
 */
public class ForwardDeadReckonLegalMoveSet implements ForwardDeadReckonComponentTransitionNotifier
{
  /** List of legal move infos into which the integer of members of this
   * collection's contents are indexes
   */
  private List<ForwardDeadReckonLegalMoveInfo> masterList;
  /** Master list crystalized into an array for fast access */
  ForwardDeadReckonLegalMoveInfo[]             masterListAsArray;
  /**
   * Contents (as a BitSet) of the legal move collections for each role
   */
  OpenBitSet[]                                 contents;
  OpenBitSet[]                                 checkPointContents;
  /** The set of roles whose legal moves are being tracked */
  private Role[]                               roles;
  private ForwardDeadReckonLegalMoveSetCollection[] preAllocatedCollections;

  private class ForwardDeadReckonLegalMoveSetIterator
                                                     implements
                                                     Iterator<ForwardDeadReckonLegalMoveInfo>
  {
    private ForwardDeadReckonLegalMoveSet parent;
    private OpenBitSet                    parentsContentsForRole;
    int                                   index;

    public ForwardDeadReckonLegalMoveSetIterator(ForwardDeadReckonLegalMoveSet parentSet,
                                                 int theRoleIndex)
    {
      parent = parentSet;
      parentsContentsForRole = parentSet.contents[theRoleIndex];
      reset();
    }

    void reset()
    {
      index = parentsContentsForRole.nextSetBit(0);
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
      index = parentsContentsForRole.nextSetBit(index + 1);
      return result;
    }

    @Override
    public void remove()
    {
      assert(false);
      // TODO Auto-generated method stub

    }
  }

  private class ForwardDeadReckonLegalMoveSetCollection
                                                       implements
                                                       Collection<ForwardDeadReckonLegalMoveInfo>
  {
    private ForwardDeadReckonLegalMoveSet parent;
    int                                   roleIndex;
    /**
     * For performance reasons (minimize GC churn) we return a single pre-allocated
     * instance of an iterator on all calls, resetting it's state on each request.
     * Obviously this means that this collection may only be used by ONE thread
     * concurrently, and iterations of it must never be nested.  This is true for all
     * current and expected uses, but it is not policable, so caveat emptor!
     */
    private final ForwardDeadReckonLegalMoveSetIterator preAllocatedIterator;

    public ForwardDeadReckonLegalMoveSetCollection(ForwardDeadReckonLegalMoveSet parentSet,
                                                   int theRoleIndex)
    {
      parent = parentSet;
      roleIndex = theRoleIndex;

      preAllocatedIterator = new ForwardDeadReckonLegalMoveSetIterator(parentSet, theRoleIndex);
    }

    @Override
    public Iterator<ForwardDeadReckonLegalMoveInfo> iterator()
    {
      preAllocatedIterator.reset();
      return preAllocatedIterator;
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
      return (int)parent.contents[roleIndex].cardinality();
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

  /**
   * Construct a legal move set with the same master associations as an
   * existing one (which is effectively used as a template)
   * @param master existing move set to use as a template
   */
  public ForwardDeadReckonLegalMoveSet(ForwardDeadReckonLegalMoveSet master)
  {
    masterList = master.masterList;
    masterListAsArray = master.masterListAsArray;
    roles = master.roles;
    contents = new OpenBitSet[roles.length];
    checkPointContents = new OpenBitSet[roles.length];
    preAllocatedCollections = new ForwardDeadReckonLegalMoveSetCollection[roles.length];

    for (int i = 0; i < roles.length; i++)
    {
      contents[i] = new OpenBitSet();
      checkPointContents[i] = new OpenBitSet();
      preAllocatedCollections[i] = new ForwardDeadReckonLegalMoveSetCollection(this, i);
    }
  }

  /**
   * Construct a new legal move set for a specified set of roles
   * @param theRoles
   */
  public ForwardDeadReckonLegalMoveSet(Role[] theRoles)
  {
    masterList = new ArrayList<>();
    masterListAsArray = null;
    contents = new OpenBitSet[theRoles.length];
    roles = new Role[theRoles.length];
    preAllocatedCollections = new ForwardDeadReckonLegalMoveSetCollection[roles.length];

    int i = 0;
    for (Role role : theRoles)
    {
      contents[i] = new OpenBitSet();
      preAllocatedCollections[i] = new ForwardDeadReckonLegalMoveSetCollection(this, i);
      this.roles[i++] = role;
    }
  }

  /**
   * Crystalize the legal move set to an optimal runtime form.  After
   * this has been called no further chnages may be made to the master list
   */
  public void crystalize()
  {
    masterListAsArray = new ForwardDeadReckonLegalMoveInfo[masterList.size()];
    masterList.toArray(masterListAsArray);
    masterList = null;
  }

  /**
   * Retrieve the master list of all legal move infos
   * @return the full list of legal move infos
   */
  public ForwardDeadReckonLegalMoveInfo[] getMasterList()
  {
    return masterListAsArray;
  }

  /* Utility methods */
  @Override
  public int hashCode()
  {
    return Arrays.hashCode(contents);
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o)
    {
      return true;
    }

    if (o instanceof ForwardDeadReckonLegalMoveSet)
    {
      ForwardDeadReckonLegalMoveSet moveSet = (ForwardDeadReckonLegalMoveSet)o;
      return Arrays.equals(moveSet.contents, contents);
    }

    return false;
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    boolean firstRole = true;

    sb.append("[ ");
    for (int roleIndex = 0; roleIndex < contents.length; roleIndex++)
    {
      int moveIndex = 1;

      if (!firstRole)
      {
        sb.append("; ");
      }
      sb.append("( ");
      for (int i = contents[roleIndex].nextSetBit(0); i >= 0; i = contents[roleIndex].nextSetBit(i + 1))
      {
        if (!first)
        {
          sb.append(", ");
        }
        sb.append(moveIndex++ + ": " + masterListAsArray[i].move);
        first = false;
      }
      sb.append(" )");
      firstRole = false;
    }
    sb.append(" ]");

    return sb.toString();
  }

  /**
   * Empty the collection
   */
  public void clear()
  {
    for (int i = 0; i < contents.length; i++)
    {
      contents[i].clear(0,masterListAsArray.length);
    }
  }

  /**
   * Copy from another legal move set
   * @param source to copy from
   */
  public void copy(ForwardDeadReckonLegalMoveSet source)
  {
    for (int i = 0; i < contents.length; i++)
    {
      contents[i].clear(0,masterListAsArray.length);
      contents[i].or(source.contents[i]);
    }
  }

  /**
   * Add a new legal move info to the master list, resolving its id
   * as we do so (i.e. - giving it a concrete index in the master list
   * which will not subsequently change)
   * @param info Legal move info to add
   * @param masterIndex asserted index we want this info to be placed at, or -1 for next available
   * @return the index of the added move's info in the master list
   */
  public int resolveId(ForwardDeadReckonLegalMoveInfo info, int masterIndex)
  {
    if ( masterIndex == -1 )
    {
      masterList.add(info);

      masterIndex = masterList.size() - 1;
    }
    else
    {
      while(masterIndex >= masterList.size())
      {
        masterList.add(null);
      }
      masterList.set(masterIndex,  info);
    }

    return masterIndex;
  }

  /**
   * Add a specified legal move to the collection.  This move must be one
   * that is already known in the master list (i.e. - resolveId() must have
   * been called with the same parameter at some time prior to this call)
   * @param info Legal move to add
   */
  public void add(ForwardDeadReckonLegalMoveInfo info)
  {
    assert(info.masterIndex != -1);
    contents[info.roleIndex].set(info.masterIndex);
  }

  @Override
  public void add(int index)
  {
    ForwardDeadReckonLegalMoveInfo info = masterListAsArray[index];
    contents[info.roleIndex].set(index);
  }

  /**
   * Remove a specified legal move to the collection.  This move should be one
   * that is already known in the master list (i.e. - resolveId() must have
   * been called with the same parameter at some time prior to this call)
   * @param info Legal move to remove
   */
  public void remove(ForwardDeadReckonLegalMoveInfo info)
  {
    assert(info.masterIndex != -1);
    contents[info.roleIndex].clear(info.masterIndex);
  }

  @Override
  public void remove(int index)
  {
    ForwardDeadReckonLegalMoveInfo info = masterListAsArray[index];
    contents[info.roleIndex].clear(index);
  }

  /**
   * Merge with another legal move set collection - result is the union
   * @param other set to merge into this set
   */
  public void merge(ForwardDeadReckonLegalMoveSet other)
  {
    for (int i = 0; i < contents.length; i++)
    {
      contents[i].or(other.contents[i]);
    }
  }

  /**
   * Intersect with another legal move set collection - result is the intersection
   * @param other set to intersect into this set
   */
  public void intersect(ForwardDeadReckonLegalMoveSet other)
  {
    for (int i = 0; i < contents.length; i++)
    {
      contents[i].and(other.contents[i]);
    }
  }

  /**
   * retrieve the set of legal moves for a specified role
   * @param roleIndex role for which we want the legal moves
   * @return collection of legal move infos
   */
  public Collection<ForwardDeadReckonLegalMoveInfo> getContents(int roleIndex)
  {
    return preAllocatedCollections[roleIndex];
    //return new ForwardDeadReckonLegalMoveSetCollection(this, roleIndex);
  }

  /**
   * retrieve the set of legal moves for a specified role
   * @param role role for which we want the legal moves
   * @return collection of legal move infos
   */
  public Collection<ForwardDeadReckonLegalMoveInfo> getContents(Role role)
  {
    for (int i = 0; i < roles.length; i++)
    {
      if (roles[i].equals(role))
      {
        return preAllocatedCollections[i];
      }
    }

    return null;
  }

  /**
   * Determine how many legal moves there are for a specified role
   * @param role
   * @return number of legals
   */
  public int getNumChoices(Role role)
  {
    for (int i = 0; i < roles.length; i++)
    {
      if (roles[i].equals(role))
      {
        return getNumChoices(i);
      }
    }

    return 0;
  }

  /**
   * Determine how many legal moves there are for a specified role
   * @param roleIndex
   * @return number of legals
   */
  public int getNumChoices(int roleIndex)
  {
    return (int)contents[roleIndex].cardinality();
  }

  /**
   * Determine if a specified move is legal for a specified role
   * @param role - role to check for
   * @param move - move being checked
   * @return whether the move is legal
   */
  public boolean isLegalMove(Role role, ForwardDeadReckonLegalMoveInfo move)
  {
    for (int i = 0; i < roles.length; i++)
    {
      if (roles[i].equals(role))
      {
        return isLegalMove(i, move);
      }
    }

    return false;
  }

  /**
   * Determine if a specified move is legal for a specified role
   * @param roleIndex - index of role to check for
   * @param move - move being checked
   * @return whether the move is legal
   */
  public boolean isLegalMove(int roleIndex, ForwardDeadReckonLegalMoveInfo move)
  {
    return contents[roleIndex].get(move.masterIndex);
  }

  @Override
  public void saveCheckpoint()
  {
    for(int i = 0; i < contents.length; i++)
    {
      checkPointContents[i].clear(0,masterListAsArray.length);
      checkPointContents[i].or(contents[i]);
    }
  }

  @Override
  public void revertToCheckpoint()
  {
    for(int i = 0; i < contents.length; i++)
    {
      contents[i].clear(0,masterListAsArray.length);
      contents[i].or(checkPointContents[i]);
    }
  }
}
