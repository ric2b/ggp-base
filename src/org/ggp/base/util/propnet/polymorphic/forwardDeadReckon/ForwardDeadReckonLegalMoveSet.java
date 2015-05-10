
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

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
  private List<ForwardDeadReckonLegalMoveInfo> alwaysLegalMoves;
  /** Master list crystalized into an array for fast access */
  ForwardDeadReckonLegalMoveInfo[]             masterListAsArray;
  /**
   * Contents (as a BitSet) of the legal move collections for each role
   */
  //OpenBitSet                                   contents;
  /** The set of roles whose legal moves are being tracked */
  private Role[]                               roles;
  private ForwardDeadReckonLegalMoveSetCollection[] preAllocatedCollections;
  //private final int[]                          cachedSizes;
  //private boolean                              hasCached = false;

  private final int[][]                        nextActive;
  private final int[][]                        prevActive;
  private final int[]                          firstActive;
  private final int[]                          lastImmutableActive;
  private final int[]                          lastActive;
  //private final int[]                          firstMutableActive;
  private final int[]                          numAlwaysActive;
  private final int[]                          numActive;

  private static final int                     INVALID_INDEX = -1;
  private static final int                     SENTINEL_INDEX = -2;

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
//      index = -1;
//      do
//      {
//        index = parent.contents.nextSetBit(index+1);
//      } while(index != -1 && parent.masterListAsArray[index].roleIndex != roleIndex);
      index = parent.firstActive[roleIndex];
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
//      do
//      {
//        index = parent.contents.nextSetBit(index+1);
//      } while(index != -1 && parent.masterListAsArray[index].roleIndex != roleIndex);
      index = parent.nextActive[roleIndex][index];
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
      //return (move != null && parent.contents.fastGet(move.masterIndex));
      return (move != null && parent.isLegalMove(move.roleIndex, move.masterIndex));
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
    alwaysLegalMoves = master.alwaysLegalMoves;
    masterListAsArray = master.masterListAsArray;
    roles = master.roles;
    //contents = new OpenBitSet(masterListAsArray != null ? masterListAsArray.length : 64);
    preAllocatedCollections = new ForwardDeadReckonLegalMoveSetCollection[roles.length];
    //cachedSizes = new int[roles.length];
    numActive = new int[roles.length];
    numAlwaysActive = new int[roles.length];
    prevActive = new int[roles.length][];
    nextActive = new int[roles.length][];
    firstActive = new int[roles.length];
    lastImmutableActive = new int[roles.length];
    lastActive = new int[roles.length];
    //firstMutableActive = new int[roles.length];

    for (int i = 0; i < roles.length; i++)
    {
      preAllocatedCollections[i] = new ForwardDeadReckonLegalMoveSetCollection(this, i);
      if ( masterListAsArray != null )
      {
        nextActive[i] = new int[masterListAsArray.length];
        prevActive[i] = new int[masterListAsArray.length];

        for(int j = 0; j < masterListAsArray.length; j++)
        {
          prevActive[i][j] = INVALID_INDEX;
        }
      }
      if ( master.lastImmutableActive[i] >= 0 )
      {
        firstActive[i] = master.firstActive[i];
        lastActive[i] = master.lastImmutableActive[i];

        for(int index = master.firstActive[i]; index >= 0; index = master.nextActive[i][index])
        {
          nextActive[i][index] = master.nextActive[i][index];
          prevActive[i][index] = master.prevActive[i][index];
          if ( index == lastImmutableActive[i] )
          {
            break;
          }
        }
      }
      else
      {
        firstActive[i] = INVALID_INDEX;
        lastActive[i] = INVALID_INDEX;
      }
      //firstMutableActive[i] = master.firstMutableActive[i];
      lastImmutableActive[i] = master.lastImmutableActive[i];
      numActive[i] = master.numAlwaysActive[i];
      numAlwaysActive[i] = master.numAlwaysActive[i];
    }

//    if ( masterListAsArray != null )
//    {
//      master.validate();
//      validate();
//    }
  }

  /**
   * Construct a new legal move set for a specified set of roles
   * @param theRoles
   */
  public ForwardDeadReckonLegalMoveSet(Role[] theRoles)
  {
    masterList = new ArrayList<>();
    alwaysLegalMoves = new ArrayList<>();
    masterListAsArray = null;
    //contents = new OpenBitSet();
    roles = new Role[theRoles.length];
    preAllocatedCollections = new ForwardDeadReckonLegalMoveSetCollection[roles.length];
    //cachedSizes = new int[roles.length];
    numActive = new int[roles.length];
    numAlwaysActive = new int[roles.length];
    prevActive = new int[roles.length][];
    nextActive = new int[roles.length][];
    firstActive = new int[roles.length];
    lastImmutableActive = new int[roles.length];
    lastActive = new int[roles.length];
    //firstMutableActive = new int[roles.length];

    int i = 0;
    for (Role role : theRoles)
    {
      preAllocatedCollections[i] = new ForwardDeadReckonLegalMoveSetCollection(this, i);
      firstActive[i] = -1;
      lastActive[i] = -1;
      lastImmutableActive[i] = -1;
      //firstMutableActive[i] = -1;
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
    //contents.ensureCapacity(masterListAsArray.length);

    for (int i = 0; i < roles.length; i++)
    {
      nextActive[i] = new int[masterListAsArray.length];
      prevActive[i] = new int[masterListAsArray.length];

      for(int j = 0; j < masterListAsArray.length; j++)
      {
        prevActive[i][j] = INVALID_INDEX;
      }

      for(ForwardDeadReckonLegalMoveInfo info : alwaysLegalMoves)
      {
        if ( info.roleIndex == i )
        {
          add(info.masterIndex);
        }
      }

      lastImmutableActive[i] = lastActive[i];
      numAlwaysActive[i] = numActive[i];
    }

    alwaysLegalMoves = null;

    //validate();
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
    return super.hashCode();//contents.hashCode();
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o)
    {
      return true;
    }

//    if (o instanceof ForwardDeadReckonLegalMoveSet)
//    {
//      ForwardDeadReckonLegalMoveSet moveSet = (ForwardDeadReckonLegalMoveSet)o;
//      return contents.equals(moveSet.contents);
//    }

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

      for(int index = firstActive[roleIndex]; index != -1; index = nextActive[roleIndex][index])
      {
        ForwardDeadReckonLegalMoveInfo info = masterListAsArray[index];

        assert(info.roleIndex == roleIndex);
        if (!first)
        {
          sb.append(", ");
        }
        sb.append(moveIndex++ + ": " + masterListAsArray[index].move);
        first = false;
      }
//      for (int i = contents.nextSetBit(0); i >= 0; i = contents.nextSetBit(i + 1))
//      {
//        if ( masterListAsArray[i].roleIndex == roleIndex )
//        {
//          if (!first)
//          {
//            sb.append(", ");
//          }
//          sb.append(moveIndex++ + ": " + masterListAsArray[i].move);
//          first = false;
//        }
//      }
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
    //contents.clear(0,masterListAsArray.length);
    for(int i = 0; i < roles.length; i++)
    {
      lastActive[i] = lastImmutableActive[i];
      numActive[i] = numAlwaysActive[i];

      if ( lastActive[i] >= 0 )
      {
        int index = nextActive[i][lastActive[i]];
        while(index >= 0)
        {
          prevActive[i][index] = INVALID_INDEX;
          index = nextActive[i][index];
        }
      }

      if ( lastActive[i] == INVALID_INDEX )
      {
        firstActive[i] = INVALID_INDEX;
      }
    }

    //validate();
  }

  /**
   * Copy from another legal move set
   * @param source to copy from
   */
  public void copy(ForwardDeadReckonLegalMoveSet source)
  {
    //source.validate();

    clear();

    for(int i = 0; i < roles.length; i++)
    {
      assert(numAlwaysActive[i] == source.numAlwaysActive[i]);
      assert(lastImmutableActive[i] == source.lastImmutableActive[i]);

      numActive[i] = source.numActive[i];
      firstActive[i] = source.firstActive[i];
      lastActive[i] = source.lastActive[i];

      for(int index = source.firstActive[i]; index != -1; index = source.nextActive[i][index])
      {
        nextActive[i][index] = source.nextActive[i][index];
        prevActive[i][index] = source.prevActive[i][index];
      }
    }

    //validate();
//    contents.clear(0,masterListAsArray.length);
//    contents.or(source.contents);
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
   * Add a specified always legal move to the collection.  This move must be one
   * that is already known in the master list (i.e. - resolveId() must have
   * been called with the same parameter at some time prior to this call)
   * @param info Legal move to add
   */
  public void addAlwaysLegal(ForwardDeadReckonLegalMoveInfo info)
  {
    assert(info.masterIndex != -1);
    //contents.set(info.masterIndex);

    //hasCached = false;

    alwaysLegalMoves.add(info);
  }

  /**
   * Add a specified legal move.  The collection must already have been crystalized
   * @param info
   */
  public void add(ForwardDeadReckonLegalMoveInfo info)
  {
    assert(info.masterIndex != -1);
    //contents.fastSet(info.masterIndex);

    //hasCached = false;
    //validate();

    int roleIndex = info.roleIndex;
    int index = info.masterIndex;

    if ( prevActive[roleIndex][index] == INVALID_INDEX )
    {
      int lastActiveIndex = lastActive[roleIndex];

      if ( lastActiveIndex >= 0 )
      {
        prevActive[roleIndex][index] = lastActiveIndex;
        nextActive[roleIndex][lastActiveIndex] = index;
      }
      else
      {
        //  Set a negative prev distinguished from INVALID_INDEX so we can use
        //  a non-INVALID_INDEX value for prev as a O(1) contains check
        prevActive[roleIndex][index] = SENTINEL_INDEX;
      }

      nextActive[roleIndex][index] = INVALID_INDEX;

      if ( firstActive[roleIndex] < 0 )
      {
        firstActive[roleIndex] = index;
      }
      lastActive[roleIndex] = index;

      numActive[roleIndex]++;
    }

    //validate();
  }

  private void validate()
  {
    for(int i = 0; i < roles.length; i++)
    {
      int count = 0;

      for(int index = firstActive[i]; index >= 0; index = nextActive[i][index])
      {
        assert(prevActive[i][index] != INVALID_INDEX);
        assert(index != nextActive[i][index]);
        count++;
      }

      assert(count == numActive[i]);
    }
  }


  @Override
  public void add(int index)
  {
    ForwardDeadReckonLegalMoveInfo info = masterListAsArray[index];
    //contents.fastSet(index);

    add(info);
  }

  public void markDirty()
  {
    //hasCached = false;
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
    //contents.fastClear(info.masterIndex);

    //hasCached = false;

    remove(info.roleIndex, info.masterIndex);
  }

  private void remove(int roleIndex, int index)
  {
    //validate();

    int[] prevActiveForRole = prevActive[roleIndex];
    int[] nextActiveForRole = nextActive[roleIndex];

    int prevIndex = prevActiveForRole[index];
    int nextIndex = nextActiveForRole[index];

    assert(prevIndex != INVALID_INDEX);

    if ( prevIndex >= 0 )
    {
      nextActiveForRole[prevIndex] = nextIndex;
    }
    else
    {
      firstActive[roleIndex] = nextIndex;
    }

    if ( nextIndex >= 0 )
    {
      prevActiveForRole[nextIndex] = prevIndex;
    }
    else
    {
      lastActive[roleIndex] = prevIndex;
    }

    prevActiveForRole[index] = INVALID_INDEX;
    numActive[roleIndex]--;

    //validate();
  }

  @Override
  public void remove(int index)
  {
    ForwardDeadReckonLegalMoveInfo info = masterListAsArray[index];
    //contents.fastClear(index);
    remove(info);
  }

  /**
   * Merge with another legal move set collection - result is the union
   * @param other set to merge into this set
   */
  public void merge(ForwardDeadReckonLegalMoveSet other)
  {
    //contents.or(other.contents);

    //validate();
    //other.validate();
    //hasCached = false;
    for(int i = 0; i < roles.length; i++)
    {
      assert(numAlwaysActive[i] == other.numAlwaysActive[i]);
      assert(lastImmutableActive[i] == other.lastImmutableActive[i]);

      for(int index = other.firstActive[i]; index != -1; index = other.nextActive[i][index])
      {
        add(index);
      }
    }
    //validate();
  }

  /**
   * Intersect with another legal move set collection - result is the intersection
   * @param other set to intersect into this set
   */
  public void intersect(ForwardDeadReckonLegalMoveSet other)
  {
    //validate();
    //other.validate();
    //contents.and(other.contents);

    //hasCached = false;
    for(int i = 0; i < roles.length; i++)
    {
      assert(numAlwaysActive[i] == other.numAlwaysActive[i]);
      assert(lastImmutableActive[i] == other.lastImmutableActive[i]);

      int nextIndex;

      for(int index = firstActive[i]; index != -1; index = nextIndex)
      {
        nextIndex = nextActive[i][index];

        if ( !other.isLegalMove(i, index) )
        {
          remove(i, index);
        }
      }
    }
    //validate();
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
//    if ( !hasCached )
//    {
//      for(int i = 0; i < roles.length; i++)
//      {
//        cachedSizes[i] = 0;
//      }
//
//      int index = contents.nextSetBit(0);
//      while(index != -1)
//      {
//        cachedSizes[masterListAsArray[index].roleIndex]++;
//        index = contents.nextSetBit(index+1);
//      }
//
//      hasCached = true;
//    }
//
//    return cachedSizes[roleIndex];
    return numActive[roleIndex];
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
    //return (masterListAsArray[move.masterIndex].roleIndex == roleIndex && contents.get(move.masterIndex));
    return isLegalMove(roleIndex, move.masterIndex);
  }

  private boolean isLegalMove(int roleIndex, int index)
  {
    return (prevActive[roleIndex][index] != INVALID_INDEX);
  }

  public ForwardDeadReckonLegalMoveInfo getRandomMove(int roleIndex)
  {
    assert(numActive[roleIndex] > 0);
    int count = rand.nextInt(numActive[roleIndex]);
    int index = firstActive[roleIndex];

    while(count-- > 0)
    {
      index = nextActive[roleIndex][index];
    }

    return masterListAsArray[index];
  }
}
