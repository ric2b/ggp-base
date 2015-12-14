package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonMaskedState;
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
public class PropNetAnalyser
{
  private static final Logger LOGGER = LogManager.getLogger();

  private final ForwardDeadReckonPropnetStateMachine mStateMachine;
  private final ForwardDeadReckonPropNet mSourceNet;
  private final TristatePropNet mTristateNet;
  private final Map<PolymorphicComponent, TristateComponent> mSourceToTarget;
  private final Set<ForwardDeadReckonProposition> mLatchBasePositive;
  private final Set<ForwardDeadReckonProposition> mLatchBaseNegative;
  private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mLatchGoalPositive;
  private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mLatchGoalNegative;
  private final List<ForwardDeadReckonMaskedState> mLatchGoalComplex;
  private boolean mFoundGoalLatches;

  public PropNetAnalyser(ForwardDeadReckonPropNet xiSourceNet,
                         ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    mStateMachine = xiStateMachine;

    // Create a tri-state network to assist with the analysis.
    mSourceNet = xiSourceNet;
    mTristateNet = new TristatePropNet(mSourceNet);

    // Clone the mapping from source to target.
    mSourceToTarget = new HashMap<>(PolymorphicPropNet.sLastSourceToTargetMap.size());
    for (PolymorphicComponent lSource : PolymorphicPropNet.sLastSourceToTargetMap.keySet())
    {
      mSourceToTarget.put(lSource, (TristateComponent)PolymorphicPropNet.sLastSourceToTargetMap.get(lSource));
    }

    // Create empty mappings for latched base propositions.
    mLatchBasePositive = new HashSet<>();
    mLatchBaseNegative = new HashSet<>();

    // Create mappings for goal latches.
    mLatchGoalPositive = new HashMap<>();
    mLatchGoalNegative = new HashMap<>();
    mLatchGoalComplex = new ArrayList<>();

    for (PolymorphicProposition lGoals[] : mSourceNet.getGoalPropositions().values())
    {
      for (PolymorphicProposition lGoal : lGoals)
      {
        mLatchGoalPositive.put(lGoal, mStateMachine.createEmptyInternalState());
        mLatchGoalNegative.put(lGoal, mStateMachine.createEmptyInternalState());
      }
    }

  }

  public void analyse(long xiTimeout)
  {
    // !! ARR Need to handle timeout.

    // Do per-proposition analysis on all the base propositions.
    for (PolymorphicComponent lSourceComp1 : mSourceNet.getBasePropositionsArray())
    {
      // Check if this proposition is a goal latch or a regular latch (or not a latch at all).
      tryLatch((ForwardDeadReckonProposition)lSourceComp1, true);
      tryLatch((ForwardDeadReckonProposition)lSourceComp1, false);
    }

    tryLatchPairs();

    postProcessGoalLatches();

    // For 2-player games, test if the game is provable fixed-sum.
    // !! ARR: assume(Tristate.UNKNOWN, Tristate.UNKNOWN, Tristate.TRUE) for the terminal prop combined with each goal
  }

  private void tryLatch(ForwardDeadReckonProposition xiProposition, boolean xiPositive)
  {
    TristateProposition lTristateProposition = getProp(xiProposition);
    Tristate lTestState = xiPositive ? Tristate.TRUE : Tristate.FALSE;
    Tristate lOtherState = xiPositive ? Tristate.FALSE : Tristate.TRUE;
    Set<ForwardDeadReckonProposition> lLatchSet = xiPositive ? mLatchBasePositive : mLatchBaseNegative;

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
  private void checkGoalLatch(ForwardDeadReckonProposition xiProposition)
  {
    Map<Role, PolymorphicProposition[]> lSourceGoals = mSourceNet.getGoalPropositions();
    Iterator<Entry<Role, PolymorphicProposition[]>> lIterator = lSourceGoals.entrySet().iterator();

    while (lIterator.hasNext())
    {
      Map.Entry<Role, PolymorphicProposition[]> lEntry = lIterator.next();
      for (PolymorphicProposition lGoal : lEntry.getValue())
      {
        Tristate lValue = getProp(lGoal).getValue(2);
        if (lValue == Tristate.TRUE)
        {
          addLatchingProposition((ForwardDeadReckonProposition)lGoal, xiProposition, true);
          mFoundGoalLatches = true;
        }
        else if (lValue == Tristate.FALSE)
        {
          addLatchingProposition((ForwardDeadReckonProposition)lGoal, xiProposition, false);
          mFoundGoalLatches = true;
        }
      }
    }
  }

  private void addLatchingProposition(ForwardDeadReckonProposition xiGoal,
                                      ForwardDeadReckonProposition xiLatchingProposition,
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

  /**
   * Find pairs of base latches which make a goal latch.
   */
  private void tryLatchPairs()
  {
    // This is expensive.  Only do it for puzzles.
    if (mStateMachine.getRoles().length != 1) return;

    // Only do it if we haven't found any goal latches so far.
    if (mFoundGoalLatches) return;

    // It's worth checking to see if any pairs of base proposition latches constitute a goal latch.  Many logic puzzles
    // contain constraints on pairs of propositions that might manifest in this way.
    //
    // Only consider positive base latches, simply because there aren't any games where we need to do this for negative
    // base latches.
    for (ForwardDeadReckonProposition lBaseLatch1 : mLatchBasePositive)
    {
      // !! ARR Do the "assume" for the first state here and then save/reload as required.
      // !! ARR Don't do both 1/2 and 2/1.
      for (ForwardDeadReckonProposition lBaseLatch2 : mLatchBasePositive)
      {
        try
        {
          mTristateNet.reset();
          // !! ARR Ideally only set up as a basic latch if it is a basic latch.
          getProp(lBaseLatch1).assume(Tristate.FALSE, Tristate.TRUE, Tristate.UNKNOWN);
          getProp(lBaseLatch2).assume(Tristate.FALSE, Tristate.TRUE, Tristate.UNKNOWN);
          checkGoalLatch(lBaseLatch1, lBaseLatch2);
        }
        catch (ContradictionException lEx) { /* Oops */ }
      }
    }
  }

  /**
   * Check whether any goals are latched in the tri-state network.  If so, add the propositions which caused it to the
   * set of latches.
   *
   * @param xiProposition - the latching proposition which MUST themselves be +ve latches.
   */
  private void checkGoalLatch(ForwardDeadReckonProposition xiProposition1,
                              ForwardDeadReckonProposition xiProposition2)
  {
    Map<Role, PolymorphicProposition[]> lSourceGoals = mSourceNet.getGoalPropositions();
    Iterator<Entry<Role, PolymorphicProposition[]>> lIterator = lSourceGoals.entrySet().iterator();

    ForwardDeadReckonMaskedState lMaskedState = new ForwardDeadReckonMaskedState(mStateMachine);
    lMaskedState.add(xiProposition1, true);
    lMaskedState.add(xiProposition2, true);

    while (lIterator.hasNext())
    {
      Map.Entry<Role, PolymorphicProposition[]> lEntry = lIterator.next();
      for (PolymorphicProposition lGoal : lEntry.getValue())
      {
        Tristate lValue = getProp(lGoal).getValue(2);
        if (lValue == Tristate.TRUE)
        {
          LOGGER.debug(xiProposition1.getName() + " & " + xiProposition2.getName() + " are a +ve pair latch for " + lGoal.getName());
          mLatchGoalComplex.add(lMaskedState);
        }
        // We only care about +ve goal latches for now
        //else if (lValue == Tristate.FALSE)
        //{
        //  LOGGER.debug(xiProposition1.getName() + " & " + xiProposition2.getName() + " are a -ve pair latch for " + lGoal.getName());
        //}
      }
    }
  }

  private TristateProposition getProp(PolymorphicProposition xiSource)
  {
    return (TristateProposition)mSourceToTarget.get(xiSource);
  }
}