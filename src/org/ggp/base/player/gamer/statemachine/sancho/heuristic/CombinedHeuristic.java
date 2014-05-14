package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * Heuristic whose value comes from combining other heuristics.
 *
 * This class encapsulates the heuristic behaviour, leaving the rest of the code dealing with a single heuristic.
 */
public class CombinedHeuristic implements Heuristic
{
  private final List<Heuristic> mHeuristics;
  private int mNumRoles;

  /**
   * Create a combined heuristic.
   *
   * @param xiHeuristics - the underlying heuristics.
   */
  public CombinedHeuristic(Heuristic... xiHeuristics)
  {
    mHeuristics = new LinkedList<>();
    for (Heuristic lHeuristic : xiHeuristics)
    {
      System.out.println("CombinedHeuristic: Adding " + lHeuristic.getClass().getSimpleName());
      mHeuristics.add(lHeuristic);
    }
  }

  private CombinedHeuristic(CombinedHeuristic copyFrom)
  {
    mNumRoles = copyFrom.mNumRoles;
    mHeuristics = new LinkedList<>();
    for (Heuristic lHeuristic : copyFrom.mHeuristics)
    {
      mHeuristics.add(lHeuristic.createIndependentInstance());
    }
  }

  /**
   * Remove all underlying heuristics
   */
  public void pruneAll()
  {
    mHeuristics.clear();
  }

  @Override
  public void tuningInitialise(ForwardDeadReckonPropnetStateMachine xiStateMachine, RoleOrdering xiRoleOrdering)
  {
    // Initialise all the underlying heuristics.
    for (Heuristic lHeuristic : mHeuristics)
    {
      lHeuristic.tuningInitialise(xiStateMachine, xiRoleOrdering);
    }

    // Remove any disabled heuristics from the list.
    prune();

    // Remember the number of roles.
    mNumRoles = xiStateMachine.getRoles().size();
  }

  @Override
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState xiState,
                                       int xiChoosingRoleIndex)
  {
    // Tell all the underlying heuristics about the interim sample.
    for (Heuristic lHeuristic : mHeuristics)
    {
      lHeuristic.tuningInterimStateSample(xiState, xiChoosingRoleIndex);
    }
  }

  @Override
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState xiState,
                                        int[] xiRoleScores)
  {
    // Tell all the underlying heuristics about the terminal sample.
    for (Heuristic lHeuristic : mHeuristics)
    {
      lHeuristic.tuningTerminalStateSample(xiState, xiRoleScores);
    }
  }

  @Override
  public void tuningComplete()
  {
    // Tell all the underlying heuristics that tuning is complete.
    for (Heuristic lHeuristic : mHeuristics)
    {
      lHeuristic.tuningComplete();
    }

    // Remove any disabled heuristics from the list.
    prune();
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState, TreeNode xiNode)
  {
    // Tell all the underlying heuristics about the new turn.
    for (Heuristic lHeuristic : mHeuristics)
    {
      lHeuristic.newTurn(xiState, xiNode);
    }
  }

  @Override
  public double[] getHeuristicValue(ForwardDeadReckonInternalMachineState xiState,
                                    ForwardDeadReckonInternalMachineState xiPreviousState)
  {
    double[] lValues = new double[mNumRoles];

    // Combine the values from the underlying heuristics.
    //
    // For now, take a simple average.  This could be enhanced to take an average that is weighted by the sample weight.
    // Additionally, as the heuristic values vary, the combined weight should decrease (reflecting the increased
    // uncertainty in the combined heuristic value).
    int lNumUsefulHeuristics = 0;
    for (Heuristic lHeuristic : mHeuristics)
    {
      double[] lNewValues = lHeuristic.getHeuristicValue(xiState, xiPreviousState);
      if (lHeuristic.getSampleWeight() != 0)
      {
        for (int lii = 0; lii < lValues.length; lii++)
        {
          lValues[lii] += lNewValues[lii];
        }
        lNumUsefulHeuristics++;
      }
    }

    if (lNumUsefulHeuristics > 1)
    {
      for (int lii = 0; lii < lValues.length; lii++)
      {
        lValues[lii] /= lNumUsefulHeuristics;
      }
    }

    return lValues;
  }

  @Override
  public int getSampleWeight()
  {
    // For now, take a simple average of the underlying weights.
    double lTotalWeight = 0;
    int lNumUsefulHeuristics = 0;

    for (Heuristic lHeuristic : mHeuristics)
    {
      int lSampleWeight = lHeuristic.getSampleWeight();
      if (lSampleWeight != 0)
      {
        lTotalWeight += lHeuristic.getSampleWeight();
        lNumUsefulHeuristics++;
      }
    }

    if (lNumUsefulHeuristics > 1)
    {
      lTotalWeight /= lNumUsefulHeuristics;
    }

    return (int)lTotalWeight;
  }

  @Override
  public boolean isEnabled()
  {
    // This combined heuristic is enabled if it have any underlying heuristics left.
    return(mHeuristics.size() != 0);
  }

  /**
   * @return whether the specified type of heuristic is included in the active list of heuristics.
   *
   * @param xiClass - the type of heuristic.
   */
  public boolean includes(Class<? extends Heuristic> xiClass)
  {
    for (Heuristic lHeuristic : mHeuristics)
    {
      if (lHeuristic.getClass() == xiClass)
      {
        return true;
      }
    }
    return false;
  }

  /**
   * Prune any disabled heuristics from the list.
   */
  private void prune()
  {
    Iterator<Heuristic> lIterator = mHeuristics.iterator();
    while (lIterator.hasNext())
    {
      Heuristic lHeuristic = lIterator.next();
      if (!lHeuristic.isEnabled())
      {
        System.out.println("CombinedHeuristic: Removing disabled " + lHeuristic.getClass().getSimpleName());
        lIterator.remove();
      }
    }
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    //  Return a suitable clone
    return new CombinedHeuristic(this);
  }
}
