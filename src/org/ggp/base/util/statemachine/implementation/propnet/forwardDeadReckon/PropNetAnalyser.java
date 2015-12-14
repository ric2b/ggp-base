package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent.ContradictionException;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent.Tristate;
import org.ggp.base.util.propnet.polymorphic.tristate.TristatePropNet;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateProposition;
import org.ggp.base.util.statemachine.Role;

/**
 * Static analysis for propnets.
 */
public class PropNetAnalyser<SourcePropType extends ForwardDeadReckonProposition>
{
  private static final Logger LOGGER = LogManager.getLogger();

  private final ForwardDeadReckonPropNet mSourceNet;
  private final TristatePropNet mTristateNet;
  private final Map<PolymorphicComponent, TristateComponent> mSourceToTarget;
  private final Set<SourcePropType> mLatchBasePositive;
  private final Set<SourcePropType> mLatchBaseNegative;
  private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mLatchGoalPositive;
  private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mLatchGoalNegative;

  @SuppressWarnings("unchecked")
  public PropNetAnalyser(ForwardDeadReckonPropNet xiSourceNet,
                         ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    // Create a tri-state network to assist with the analysis.
    mSourceNet = xiSourceNet;
    mTristateNet = new TristatePropNet(mSourceNet);

    // Clone the mapping from source to target.
    mSourceToTarget = new HashMap<>(PolymorphicPropNet.sLastSourceToTargetMap.size());
    mSourceToTarget.putAll((Map<? extends PolymorphicComponent,
                                ? extends TristateComponent>)PolymorphicPropNet.sLastSourceToTargetMap);

    // Create empty mappings for latched base propositions.
    mLatchBasePositive = new HashSet<>();
    mLatchBaseNegative = new HashSet<>();

    // Create mappings for goal latches.
    mLatchGoalPositive = new HashMap<>();
    mLatchGoalNegative = new HashMap<>();

    for (PolymorphicProposition lGoals[] : mSourceNet.getGoalPropositions().values())
    {
      for (PolymorphicProposition lGoal : lGoals)
      {
        mLatchGoalPositive.put(lGoal, xiStateMachine.createEmptyInternalState());
        mLatchGoalNegative.put(lGoal, xiStateMachine.createEmptyInternalState());
      }
    }

  }

  @SuppressWarnings("unchecked")
  public void analyse(long xiTimeout)
  {
    // !! ARR Need to handle timeout.

    // Do per-proposition analysis on all the base propositions.
    for (PolymorphicComponent lSourceComp1 : mSourceNet.getBasePropositionsArray())
    {
      // Check if this proposition is a goal latch or a regular latch (or not a latch at all).
      tryLatch((SourcePropType)lSourceComp1, true);
      tryLatch((SourcePropType)lSourceComp1, false);
    }

    // To assist with puzzles, if we haven't found any goal latches yet, it's worth checking to see if any pairs of
    // base proposition latches constitute a goal latch.  Many logic puzzles contain constraints on pairs of
    // propositions that might manifest in this way.  (Futoshiki, Hidato, Hitori, Queens Puzzles are all examples and
    // it's particularly important for the unguided version of the latter.)
    checkForLatchPairs();

    postProcessGoalLatches();

    // For 2-player games, test if the game is provable fixed-sum.
    // !! ARR: assume(Tristate.UNKNOWN, Tristate.UNKNOWN, Tristate.TRUE) for the terminal prop combined with each goal
  }

  private void tryLatch(SourcePropType xiProposition, boolean xiPositive)
  {
    TristateProposition lTristateProposition = (TristateProposition)mSourceToTarget.get(xiProposition);
    Tristate lTestState = xiPositive ? Tristate.TRUE : Tristate.FALSE;
    Tristate lOtherState = xiPositive ? Tristate.FALSE : Tristate.TRUE;
    Set<SourcePropType> lLatchSet = xiPositive ? mLatchBasePositive : mLatchBaseNegative;

    try
    {
      mTristateNet.reset();
      lTristateProposition.assume(Tristate.UNKNOWN, lTestState, Tristate.UNKNOWN);
      if (lTristateProposition.getValue(2) == lTestState)
      {
        LOGGER.info(xiProposition.getName() + " is a basic " + (xiPositive ? "+" : "-") + "ve latch");
        lLatchSet.add(xiProposition);

        if (xiPositive)
        {
          checkGoalLatch(xiProposition);
        }
        return;
      }

      mTristateNet.reset(); // !! ARR This shouldn't be necessary, but it is, which implies a tri-state propagation bug
      lTristateProposition.assume(lOtherState, lTestState, Tristate.UNKNOWN);
      if (lTristateProposition.getValue(2) == lTestState)
      {
        LOGGER.info(xiProposition.getName() + " is a complex " + (xiPositive ? "+" : "-") + "ve latch");
        lLatchSet.add(xiProposition);

        if (xiPositive)
        {
          checkGoalLatch(xiProposition);
        }
      }
    }
    catch (ContradictionException lEx) { /* Do nothing */ }
  }

  /**
   * Check whether any goals are latched in the tri-state network.  If so, add the proposition which caused it to the
   * set of latches.
   *
   * @param xiProposition - the latching proposition which MUST itself be a +ve latch.
   */
  @SuppressWarnings("unchecked")
  private void checkGoalLatch(SourcePropType xiProposition)
  {
    Map<Role, PolymorphicProposition[]> lSourceGoals = mSourceNet.getGoalPropositions();
    Iterator<Entry<Role, PolymorphicProposition[]>> lIterator = lSourceGoals.entrySet().iterator();

    while (lIterator.hasNext())
    {
      Map.Entry<Role, PolymorphicProposition[]> lEntry = lIterator.next();
      for (PolymorphicProposition lGoal : lEntry.getValue())
      {
        Tristate lValue = ((TristateProposition)mSourceToTarget.get(lGoal)).getValue(2);
        if (lValue == Tristate.TRUE)
        {
          addLatchingProposition((SourcePropType)lGoal, xiProposition, true);
        }
        else if (lValue == Tristate.FALSE)
        {
          addLatchingProposition((SourcePropType)lGoal, xiProposition, false);
        }
      }
    }
  }

  private void addLatchingProposition(SourcePropType xiGoal,
                                      SourcePropType xiLatchingProposition,
                                      boolean xiPositive)
  {
    Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lGoalMap = (xiPositive ? mLatchGoalPositive :
                                                                                                mLatchGoalNegative);
    ForwardDeadReckonInternalMachineState lExisting = lGoalMap.get(xiGoal);
    lExisting.add(xiLatchingProposition.getInfo());
  }

  private void postProcessGoalLatches()
  {
    // Post-process the goal latches to remove any goals for which no latches were found.
    Iterator<Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState>> lIterator =
                                                                               mLatchGoalPositive.entrySet().iterator();
    while (lIterator.hasNext())
    {
      Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry = lIterator.next();
      PolymorphicProposition lGoal = lEntry.getKey();
      ForwardDeadReckonInternalMachineState lLatches = lEntry.getValue();

      if (lLatches.size() != 0)
      {
        LOGGER.info("Goal '" + lGoal.getName() + "' is positively latched by any of: " + lLatches);
      }
      else
      {
        lIterator.remove();
      }
    }

    if (mLatchGoalPositive.isEmpty())
    {
      LOGGER.info("No positive goal latches");
      mLatchGoalPositive = null;
    }

    lIterator = mLatchGoalNegative.entrySet().iterator();
    while (lIterator.hasNext())
    {
      Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry = lIterator.next();
      PolymorphicProposition lGoal = lEntry.getKey();
      ForwardDeadReckonInternalMachineState lLatches = lEntry.getValue();

      if (lLatches.size() != 0)
      {
        LOGGER.info("Goal '" + lGoal.getName() + "' is negatively latched by any of: " + lLatches);
      }
      else
      {
        lIterator.remove();
      }
    }

    if (mLatchGoalNegative.isEmpty())
    {
      LOGGER.info("No negative goal latches");
      mLatchGoalNegative = null;
    }
  }

  private void checkForLatchPairs()
  {
    // This is expensive.  Only do it if we don't have any goal latches yet.
  }
}