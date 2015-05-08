
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

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
  OpenBitSet                                   contents;
  /** The set of roles whose legal moves are being tracked */
  private Role[]                               roles;
  private ForwardDeadReckonLegalMoveSetCollection[] preAllocatedCollections;
  private final int[]                          cachedSizes;
  private boolean                              hasCached = false;
  private final Random                         rand = new Random();

  private class ForwardDeadReckonLegalMoveSetIterator
                                                     implements
                                                     Iterator<ForwardDeadReckonLegalMoveInfo>
  {
    private final ForwardDeadReckonLegalMoveSet parent;
    int                                         index;
    private final int                           roleIndex;

    public ForwardDeadReckonLegalMoveSetIterator(ForwardDeadReckonLegalMoveSet parentSet,
                                                 int theRoleIndex)
    {
      parent = parentSet;
      roleIndex = theRoleIndex;
      reset();
    }

    void reset()
    {
      index = -1;
      do
      {
        index = parent.contents.nextSetBit(index+1);
      } while(index != -1 && parent.masterListAsArray[index].roleIndex != roleIndex);
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
      do
      {
        index = parent.contents.nextSetBit(index+1);
      } while(index != -1 && parent.masterListAsArray[index].roleIndex != roleIndex);
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
      return (move != null && parent.contents.fastGet(move.masterIndex));
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
      return parent.getNumChoices(roleIndex) == 0;
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
      return parent.getNumChoices(roleIndex);
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
    contents = new OpenBitSet(masterListAsArray != null ? masterListAsArray.length : 64);
    preAllocatedCollections = new ForwardDeadReckonLegalMoveSetCollection[roles.length];
    cachedSizes = new int[roles.length];

    for (int i = 0; i < roles.length; i++)
    {
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
    contents = new OpenBitSet();
    roles = new Role[theRoles.length];
    preAllocatedCollections = new ForwardDeadReckonLegalMoveSetCollection[roles.length];
    cachedSizes = new int[roles.length];

    int i = 0;
    for (Role role : theRoles)
    {
      preAllocatedCollections[i] = new ForwardDeadReckonLegalMoveSetCollection(this, i);
      this.roles[i++] = role;
    }
  }

  /**
   * Crystalize the legal move set to an optimal runtime form.  After
   * this has been called no further changes may be made to the master list
   */
  public void crystalize()
  {
    masterListAsArray = new ForwardDeadReckonLegalMoveInfo[masterList.size()];
    masterList.toArray(masterListAsArray);
    masterList = null;
    contents.ensureCapacity(masterListAsArray.length);
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
    return contents.hashCode();
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
      return contents.equals(moveSet.contents);
    }

    return false;
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    boolean firstRole = true;

    sb.append("[ ");
    for (int roleIndex = 0; roleIndex < roles.length; roleIndex++)
    {
      boolean first = true;
      int moveIndex = 1;

      sb.append("( ");
      for (int i = contents.nextSetBit(0); i >= 0; i = contents.nextSetBit(i + 1))
      {
        if ( masterListAsArray[i].roleIndex == roleIndex )
        {
          if (!first)
          {
            sb.append(", ");
          }
          sb.append(moveIndex++ + ": " + masterListAsArray[i].move);
          first = false;
        }
      }
      sb.append(" )");
      if (!firstRole)
      {
        sb.append("; ");
      }
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
    contents.clear(0,masterListAsArray.length);
  }

  /**
   * Copy from another legal move set
   * @param source to copy from
   */
  public void copy(ForwardDeadReckonLegalMoveSet source)
  {
    contents.clear(0,masterListAsArray.length);
    contents.or(source.contents);
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
  public void addSafe(ForwardDeadReckonLegalMoveInfo info)
  {
    assert(info.masterIndex != -1);
    contents.set(info.masterIndex);

    hasCached = false;
  }

  /**
   * Add a specified legal move.  The collection must already have been crystalized
   * @param info
   */
  public void add(ForwardDeadReckonLegalMoveInfo info)
  {
    assert(info.masterIndex != -1);
    contents.fastSet(info.masterIndex);

    hasCached = false;
  }


  @Override
  public void add(int index)
  {
    ForwardDeadReckonLegalMoveInfo info = masterListAsArray[index];
    contents.fastSet(index);
  }

  public void markDirty()
  {
    hasCached = false;
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
    contents.fastClear(info.masterIndex);

    hasCached = false;
  }

  @Override
  public void remove(int index)
  {
    ForwardDeadReckonLegalMoveInfo info = masterListAsArray[index];
    contents.fastClear(index);
  }

  /**
   * Merge with another legal move set collection - result is the union
   * @param other set to merge into this set
   */
  public void merge(ForwardDeadReckonLegalMoveSet other)
  {
    contents.or(other.contents);

    hasCached = false;
  }

  /**
   * Intersect with another legal move set collection - result is the intersection
   * @param other set to intersect into this set
   */
  public void intersect(ForwardDeadReckonLegalMoveSet other)
  {
    contents.and(other.contents);

    hasCached = false;
  }

  /**
   * retrieve the set of legal moves for a specified role
   * @param roleIndex role for which we want the legal moves
   * @return collection of legal move infos
   */
  public Collection<ForwardDeadReckonLegalMoveInfo> getContents(int roleIndex)
  {
    return preAllocatedCollections[roleIndex];
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
    if ( !hasCached )
    {
      for(int i = 0; i < roles.length; i++)
      {
        cachedSizes[i] = 0;
      }

      int index = contents.nextSetBit(0);
      while(index != -1)
      {
        cachedSizes[masterListAsArray[index].roleIndex]++;
        index = contents.nextSetBit(index+1);
      }

      hasCached = true;
    }

    return cachedSizes[roleIndex];
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
    return (masterListAsArray[move.masterIndex].roleIndex == roleIndex && contents.get(move.masterIndex));
  }

  public ForwardDeadReckonLegalMoveInfo getRandomMove(int roleIndex)
  {
    int index = rand.nextInt(masterListAsArray.length);

    do
    {
      index = contents.nextSetBit(index+1);
      if ( index == -1 )
      {
        index = contents.nextSetBit(0);
      }
    } while(masterListAsArray[index].roleIndex != roleIndex);

    return masterListAsArray[index];
  }
}
