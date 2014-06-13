
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import org.apache.lucene.util.OpenBitSet;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.statemachine.MachineState;

/**
 * Internal representation of a machine state, intended for efficient runtime usage
 */
public class ForwardDeadReckonInternalMachineState implements ForwardDeadReckonComponentTransitionNotifier
{
  /**
   * An iterator over the propositions that are set in a machine state.
   *
   * This iterator must be used by a single thread at a time and cannot be used in nested form.
   */
  public static class InternalMachineStateIterator implements Iterator<ForwardDeadReckonPropositionInfo>
  {
    private ForwardDeadReckonInternalMachineState mState;
    int                                           mIndex;

    /**
     * Reset the iterator for the specified state.
     *
     * @param xiState - the state.
     */
    public void reset(ForwardDeadReckonInternalMachineState xiState)
    {
      this.mState = xiState;
      mIndex = xiState.contents.nextSetBit(0);
    }

    @Override
    public boolean hasNext()
    {
      return (mIndex != -1);
    }

    @Override
    public ForwardDeadReckonPropositionInfo next()
    {
      ForwardDeadReckonPropositionInfo result = mState.infoSet[mIndex];
      mIndex = mState.contents.nextSetBit(mIndex + 1);
      return result;
    }

    @Override
    public void remove()
    {
      assert(false) : "InternalMachineStateIterator doesn't support remove()";
    }
  }

  // Master list of propositions which may be included or not in the state.
  private final ForwardDeadReckonPropositionInfo[] infoSet;

  // Optional heuristic data associated with the state.
  private HashMap<Heuristic, Object>               heuristicData = null;

  // BitSet of which propositions are true in the state
  private final OpenBitSet                         contents;

  /**
   * Whether the state is one handled by the X-split of the state machine (else the O split)
   */
  public boolean                                   isXState = false;

  /**
   * Construct a new empty state for the given set of possible base propositions
   * @param masterInfoSet list of the possible base propositions that may occur
   */
  public ForwardDeadReckonInternalMachineState(ForwardDeadReckonPropositionInfo[] masterInfoSet)
  {
    infoSet = masterInfoSet;
    contents = new OpenBitSet(infoSet.length);
  }

  /**
   * Clone an existing state.
   *
   * Note - this does NOT preserve any attached heuristic info. !! ARR Doesn't seem to be true
   *
   * @param copyFrom - the state to copy.
   */
  public ForwardDeadReckonInternalMachineState(ForwardDeadReckonInternalMachineState copyFrom)
  {
    this(copyFrom.infoSet);
    copy(copyFrom);
  }

  /**
   * Getter
   * @return BitSet of active base propositions
   */
  public OpenBitSet getContents()
  {
    return contents;
  }

  /**
   * Add a proposition to the set of those present in the state
   * @param info
   */
  public void add(ForwardDeadReckonPropositionInfo info)
  {
    contents.set(info.index);
  }

  @Override
  public void add(int index)
  {
    contents.set(index);
  }

  /**
   * Test whether a specified proposition is present in the state
   * @param info Meta info for the proposition to test
   * @return whether it is present
   */
  public boolean contains(ForwardDeadReckonPropositionInfo info)
  {
    return contents.get(info.index);
  }

  /**
   * @return the number of propositions set in the state
   */
  public long size()
  {
    return contents.cardinality();
  }

  /**
   * XOR this state with a specified other state (leaving the state difference)
   * @param other state to XOR into this one
   */
  public void xor(ForwardDeadReckonInternalMachineState other)
  {
    contents.xor(other.contents);
  }

  /**
   * Invert this state (propositions present cease to be so, those not become so)
   */
  public void invert()
  {
    contents.flip(0, infoSet.length - 1);
  }

  /**
    * Merge this state with a specified other state (leaving the union)
    * @param other state to merge into this one
    */
  public void merge(ForwardDeadReckonInternalMachineState other)
  {
    contents.or(other.contents);
  }

  /**
   * Take the intersection of this state with a specified other state (leaving that intersection)
   * @param other state to AND into this one
   */
  public void intersect(ForwardDeadReckonInternalMachineState other)
  {
    contents.and(other.contents);
  }

  /**
   * Test whether another state has any overlap (in set propositions) with this one
   * @param other Other state to check for intersection with
   * @return whether any intersection exists
   */
  public boolean intersects(ForwardDeadReckonInternalMachineState other)
  {
    return contents.intersects(other.contents);
  }

  /**
   * Determine the size of intersection (num common propositions) with a specified other state.
   *
   * @param other - Other state to check the intersection size with.
   *
   * @return Number of common propositions
   */
  public int intersectionSize(ForwardDeadReckonInternalMachineState other)
  {
    return (int)OpenBitSet.intersectionCount(contents, other.contents);
  }

  /**
   * Copy another state into this one.
   *
   * @param other State to copy
   */
  public void copy(ForwardDeadReckonInternalMachineState other)
  {
    contents.xor(contents);
    contents.or(other.contents);

    isXState = other.isXState;

    if ( other.heuristicData != null )
    {
      if (heuristicData == null)
      {
        heuristicData = new HashMap<>();
      }
      heuristicData.putAll(other.heuristicData);
    }
  }

  /**
   * Retrieve a (crude) measure of the distance between two states in state space
   * @param other State to compare with
   * @return Distance in the range [0,1]
   */
  public double distance(ForwardDeadReckonInternalMachineState other)
  {
    long diff = OpenBitSet.xorCount(contents, other.contents);
    long jointSize = OpenBitSet.unionCount(contents, other.contents);
    return (double)diff / jointSize;
  }

  /**
   * Clear all propositions leaving an empty state
   */
  public void clear()
  {
    contents.xor(contents);
  }

  /**
   * Remove a specified proposition from the state
   * @param info meta info of the proposition to remove
   */
  public void remove(ForwardDeadReckonPropositionInfo info)
  {
    contents.clear(info.index);
  }

  @Override
  public void remove(int index)
  {
    contents.clear(index);
  }

  /**
   * Convert to a ggp-base MachineState
   * @return MachineState instance corresponding to this state
   */
  public MachineState getMachineState()
  {
    MachineState result = new MachineState(new HashSet<GdlSentence>());

    for (int i = contents.nextSetBit(0); i >= 0; i = contents.nextSetBit(i + 1))
    {
      result.getContents().add(infoSet[i].sentence);
    }

    return result;
  }

  /**
   * Store data associated with a heuristic for this machine state.
   *
   * @param xiHeuristic - the heuristic.
   * @param xiData      - the data to store.
   */
  public void putHeuristicData(Heuristic xiHeuristic, Object xiData)
  {
    if ( heuristicData == null )
    {
      heuristicData = new HashMap<>();
    }
    heuristicData.put(xiHeuristic, xiData);
  }

  /**
   * Retrieve data for a heuristic, previously stored with {@link #putHeuristicData()}.
   *
   * @param xiHeuristic - the heuristic.
   * @return the previously stored data.
   */
  public Object getHeuristicData(Heuristic xiHeuristic)
  {
    return (heuristicData == null ? null : heuristicData.get(xiHeuristic));
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

    if (o instanceof ForwardDeadReckonInternalMachineState)
    {
      ForwardDeadReckonInternalMachineState state = (ForwardDeadReckonInternalMachineState)o;
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
    for (int i = contents.nextSetBit(0); i >= 0; i = contents.nextSetBit(i + 1))
    {
      if (!first)
      {
        sb.append(", ");
      }
      sb.append(infoSet[i].sentence);
      first = false;
    }
    sb.append(" )");

    return sb.toString();
  }

  /**
   * Determine whether a specified other state is a subset of this state
   * @param other State to test
   * @return whether it is a subset
   */
  public boolean contains(ForwardDeadReckonInternalMachineState other)
  {
    return (OpenBitSet.intersectionCount(contents, other.contents) == other.contents.cardinality());
  }
}
