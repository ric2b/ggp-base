
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.statemachine.MachineState;

public class ForwardDeadReckonInternalMachineState implements Iterable<ForwardDeadReckonPropositionInfo>,
                                                              ForwardDeadReckonComponentTransitionNotifier
{
  private class InternalMachineStateIterator implements Iterator<ForwardDeadReckonPropositionInfo>
  {
    private ForwardDeadReckonInternalMachineState parent;
    int                                           index;

    public InternalMachineStateIterator(ForwardDeadReckonInternalMachineState parent)
    {
      this.parent = parent;
      index = parent.contents.nextSetBit(0);
    }

    @Override
    public boolean hasNext()
    {
      return (index != -1);
    }

    @Override
    public ForwardDeadReckonPropositionInfo next()
    {
      ForwardDeadReckonPropositionInfo result = parent.infoSet[index];
      index = parent.contents.nextSetBit(index + 1);
      return result;
    }

    @Override
    public void remove()
    {
      // TODO Auto-generated method stub
    }
  }

  private ForwardDeadReckonPropositionInfo[] infoSet;
  private final HashMap<Heuristic, Object>   heuristicData;
  BitSet                                     contents = new BitSet();
  //Set<ForwardDeadReckonPropositionCrossReferenceInfo> contents = new HashSet<ForwardDeadReckonPropositionCrossReferenceInfo>();
  public boolean                             isXState = false;

  public ForwardDeadReckonInternalMachineState(ForwardDeadReckonPropositionInfo[] infoSet)
  {
    this.infoSet = infoSet;
    heuristicData = new HashMap<>();
  }

  public ForwardDeadReckonInternalMachineState(ForwardDeadReckonInternalMachineState copyFrom)
  {
    this.infoSet = copyFrom.infoSet;
    heuristicData = new HashMap<>();
    copy(copyFrom);
  }

  public void add(ForwardDeadReckonPropositionInfo info)
  {
    contents.set(info.index);
  }

  @Override
  public void add(int index)
  {
    //assert(index >= 0 && index < 1000);
    contents.set(index);
  }

  public boolean contains(ForwardDeadReckonPropositionInfo info)
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

  public void invert()
  {
    contents.flip(0, infoSet.length - 1);
  }

  public void merge(ForwardDeadReckonInternalMachineState other)
  {
    contents.or(other.contents);
  }

  public void intersect(ForwardDeadReckonInternalMachineState other)
  {
    contents.and(other.contents);
  }

  public boolean intersects(ForwardDeadReckonInternalMachineState other)
  {
    return contents.intersects(other.contents);
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

    heuristicData.putAll(other.heuristicData);
  }

  public double distance(ForwardDeadReckonInternalMachineState other)
  {
    ForwardDeadReckonInternalMachineState temp = new ForwardDeadReckonInternalMachineState(other);

    temp.xor(this);
    int diff = temp.contents.cardinality();
    temp.copy(other);
    temp.merge(this);
    int jointSize = temp.contents.cardinality();

    return (double)diff / jointSize;
  }

  public void clear()
  {
    contents.clear();
  }

  public void remove(ForwardDeadReckonPropositionInfo info)
  {
    contents.clear(info.index);
  }

  @Override
  public void remove(int index)
  {
    //assert(index >= 0 && index < 1000);
    contents.clear(index);
  }

  public MachineState getMachineState()
  {
    //ProfileSection methodSection = new ProfileSection("InternalMachineState.getMachineState");
    //try
    {
      MachineState result = new MachineState(new HashSet<GdlSentence>());

      for (int i = contents.nextSetBit(0); i >= 0; i = contents.nextSetBit(i + 1))
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

  /**
   * Store data associated with a heuristic for this machine state.
   *
   * @param xiHeuristic - the heuristic.
   * @param xiData      - the data to store.
   */
  public void putHeuristicData(Heuristic xiHeuristic, Object xiData)
  {
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
    return heuristicData.get(xiHeuristic);
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

    if ((o != null) && (o instanceof ForwardDeadReckonInternalMachineState))
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

  @Override
  public Iterator<ForwardDeadReckonPropositionInfo> iterator()
  {
    return new InternalMachineStateIterator(this);
  }

  public boolean contains(ForwardDeadReckonInternalMachineState other)
  {
    ForwardDeadReckonInternalMachineState temp = new ForwardDeadReckonInternalMachineState(other);

    temp.intersect(this);
    return other.contents.cardinality() == temp.contents.cardinality();
  }
}
