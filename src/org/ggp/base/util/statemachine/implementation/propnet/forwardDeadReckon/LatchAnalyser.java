package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.MaskedStateGoalLatch;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent.ContradictionException;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateComponent.Tristate;
import org.ggp.base.util.propnet.polymorphic.tristate.TristatePropNet;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateProposition;
import org.ggp.base.util.statemachine.Role;

/**
 * Latch analysis.
 *
 * Users should call analyse() once and then discard the latch analyser.
 */
@SuppressWarnings("synthetic-access")
public class LatchAnalyser
{
  private static final Logger LOGGER = LogManager.getLogger();

  // !! ARR Do saving and reloading of latch analysis results.

  private final ForwardDeadReckonPropnetStateMachine mStateMachine;
  private final ForwardDeadReckonPropNet mSourceNet;
  private final TristatePropNet mTristateNet;
  private final Map<PolymorphicComponent, TristateComponent> mSourceToTarget;
  private final Latches mLatches;

  private final List<MaskedStateGoalLatch> mComplexPositiveGoalLatchList;
  private long mDeadline;

  /**
   * Latch analysis results.
   *
   * All public methods are thread-safe and read-only.
   */
  public static class Latches
  {
    // Private variables are largely manipulated by the enclosing LatchAnalyser class.  Once an instance of this class
    // has been returned by the latch analyser, the only possible access is through the public methods of this class.
    private final Set<ForwardDeadReckonProposition> mPositiveBaseLatches;
    private final Set<ForwardDeadReckonProposition> mNegativeBaseLatches;
    private final ForwardDeadReckonInternalMachineState mPositiveBaseLatchMask;
    private final ForwardDeadReckonInternalMachineState mNegativeBaseLatchMask;
    private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mSimplePositiveGoalLatches;
    private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mSimpleNegativeGoalLatches;
    private MaskedStateGoalLatch[] mComplexPositiveGoalLatches;
    private boolean mFoundPositiveBaseLatches;
    private boolean mFoundNegativeBaseLatches;
    private boolean mFoundSimplePositiveGoalLatches;
    private boolean mFoundSimpleNegativeGoalLatches;
    private boolean mFoundComplexPositiveGoalLatches;
    private boolean mAllRolesHavePositiveGoalLatches;
    private final Map<Role, ForwardDeadReckonInternalMachineState> mPerRolePositiveGoalLatchMasks;
    private final Map<Role,int[]> mStaticGoalRanges;
    private final Role[] mRoles;

    /**
     * Create a a set of latch analysis results.
     *
     * @param xiSourceNet - the source propnet.  Will NOT be referenced after the constructor returns.
     * @param xiStateMachine - the state machine.  Will NOT be referenced after the constructor returns.
     */
    public Latches(ForwardDeadReckonPropNet xiSourceNet,
                   ForwardDeadReckonPropnetStateMachine xiStateMachine)
    {
      // Create empty mappings for latched base propositions.
      mPositiveBaseLatches = new HashSet<>();
      mNegativeBaseLatches = new HashSet<>();
      mPositiveBaseLatchMask = xiStateMachine.createEmptyInternalState();
      mNegativeBaseLatchMask = xiStateMachine.createEmptyInternalState();

      // Create mappings for goal latches.
      mSimplePositiveGoalLatches = new HashMap<>();
      mSimpleNegativeGoalLatches = new HashMap<>();

      for (PolymorphicProposition lGoals[] : xiSourceNet.getGoalPropositions().values())
      {
        for (PolymorphicProposition lGoal : lGoals)
        {
          mSimplePositiveGoalLatches.put(lGoal, xiStateMachine.createEmptyInternalState());
          mSimpleNegativeGoalLatches.put(lGoal, xiStateMachine.createEmptyInternalState());
        }
      }

      mPerRolePositiveGoalLatchMasks = new HashMap<>();
      mStaticGoalRanges = new HashMap<>();

      // Store off the roles.
      mRoles = xiStateMachine.getRoles();
    }

    /**
     * @return whether any positively latched goals have been identified.
     */
    public boolean hasPositivelyLatchedGoals()
    {
      return mFoundSimplePositiveGoalLatches || mFoundComplexPositiveGoalLatches;
    }

    /**
     * @return whether any negatively latched goals have been identified.
     */
    public boolean hasNegativelyLatchedGoals()
    {
      return mFoundSimpleNegativeGoalLatches;
    }

    /**
     * @return whether the specified proposition is a positive latch.
     *
     * @param xiProposition - the proposition to test.
     */
    public boolean isPositivelyLatchedBaseProp(PolymorphicProposition xiProposition)
    {
      return mPositiveBaseLatches.contains(xiProposition);
    }

    /**
     * @return whether the specified proposition is a negative latch.
     *
     * @param xiProposition - the proposition to test.
     */
    public boolean isNegativelyLatchedBaseProp(PolymorphicProposition xiProposition)
    {
      return mNegativeBaseLatches.contains(xiProposition);
    }

    /**
     * @return a mask of all positively latched base props, or null if there are none.
     *
     * WARNING: Callers MUST NOT modify the returned mask.
     */
    public ForwardDeadReckonInternalMachineState getPositiveBaseLatches()
    {
      return mFoundPositiveBaseLatches ? mPositiveBaseLatchMask : null;
    }

    /**
     * @return the number of positively latched base props.
     */
    public long getNumPositiveBaseLatches()
    {
      return mFoundPositiveBaseLatches ? mPositiveBaseLatchMask.size() : 0;
    }

    /**
     * @return the number of negatively latched base props.
     */
    public long getNumNegativeBaseLatches()
    {
      return mFoundNegativeBaseLatches ? mNegativeBaseLatchMask.size() : 0;
    }

    /**
     * WARNING: Callers should almost always call ForwardDeadReckonPropnetStateMachine.scoresAreLatched instead
     *          (because it also handles emulated goals).
     *
     * @param xiState - state to test for latched score in
     * @return true if all roles' scores are latched
     */
    public boolean scoresAreLatched(ForwardDeadReckonInternalMachineState xiState)
    {
      if ((!mFoundComplexPositiveGoalLatches) && (!mAllRolesHavePositiveGoalLatches))
      {
        return false;
      }

      // Check for pair latches.  (If there are any, it must be a single-player game.)
      for (MaskedStateGoalLatch lMaskedState : mComplexPositiveGoalLatches)
      {
        if (lMaskedState.matches(xiState))
        {
          assert(mRoles.length == 1) : "Pair latches only used in single-player games";
          return true;
        }
      }

      // It's only worth checking simple latches if all roles have latches.
      if (!mAllRolesHavePositiveGoalLatches)
      {
        return false;
      }

      // Check for single latches.
      for (Role lRole : mRoles)
      {
        ForwardDeadReckonInternalMachineState lRoleLatchMask = mPerRolePositiveGoalLatchMasks.get(lRole);

        if (!xiState.intersects(lRoleLatchMask))
        {
          return false;
        }
      }

      return true;
    }

    /**
     * Get the latched range of possible scores for a given role in a given state
     *
     * WARNING: Callers should almost always call ForwardDeadReckonPropnetStateMachine.getLatchedScoreRange instead.
     *
     * @param xiState - the state
     * @param xiRole - the role
     * @param xiGoals - the goal propositions for the specified role
     * @param xoRange - array of length 2 to contain [min,max]
     */
    public void getLatchedScoreRange(ForwardDeadReckonInternalMachineState xiState,
                                     Role xiRole,
                                     PolymorphicProposition[] xiGoals,
                                     int[] xoRange)
    {
      assert(xoRange.length == 2);

      // Initialise to sentinel values
      xoRange[0] = Integer.MAX_VALUE;
      xoRange[1] = -Integer.MAX_VALUE;
      int[] lStaticGoalRange = null;

      if ((mFoundSimplePositiveGoalLatches) ||
          (mFoundSimpleNegativeGoalLatches) ||
          (mFoundComplexPositiveGoalLatches) ||
          ((lStaticGoalRange = mStaticGoalRanges.get(xiRole)) == null))
      {
        // Check for pair latches.  (If there are any, it must be a single-player game.)
        for (MaskedStateGoalLatch lMaskedState : mComplexPositiveGoalLatches)
        {
          if (lMaskedState.matches(xiState))
          {
            int lValue = lMaskedState.getGoalValue();
            xoRange[0] = lValue;
            xoRange[1] = lValue;
            return;
          }
        }

        for (PolymorphicProposition goalProp : xiGoals)
        {
          ForwardDeadReckonInternalMachineState negativeMask = null;
          int latchedScore = Integer.parseInt(goalProp.getName().getBody().get(1).toString());

          if (mSimplePositiveGoalLatches != null)
          {
            ForwardDeadReckonInternalMachineState positiveMask = mSimplePositiveGoalLatches.get(goalProp);
            if (positiveMask != null && xiState.intersects(positiveMask))
            {
              xoRange[0] = latchedScore;
              xoRange[1] = latchedScore;
              break;
            }
          }
          if (mSimpleNegativeGoalLatches != null)
          {
            negativeMask = mSimpleNegativeGoalLatches.get(goalProp);
          }
          if (negativeMask == null || !xiState.intersects(negativeMask))
          {
            //  This is still a possible score
            if (latchedScore < xoRange[0])
            {
              xoRange[0] = latchedScore;
            }
            if (latchedScore > xoRange[1])
            {
              xoRange[1] = latchedScore;
            }
          }
        }

        if ((!mFoundSimplePositiveGoalLatches) && (!mFoundSimpleNegativeGoalLatches))
        {
          // There are no latches.  Cache the range so that we don't calculate it again.
          lStaticGoalRange = new int[2];

          lStaticGoalRange[0] = xoRange[0];
          lStaticGoalRange[1] = xoRange[1];

          mStaticGoalRanges.put(xiRole, lStaticGoalRange);
        }
      }
      else
      {
        xoRange[0] = lStaticGoalRange[0];
        xoRange[1] = lStaticGoalRange[1];
      }
    }
  }

  /**
   * Create a latch analyser.  Callers should call the analyse method and then discard the analyser.
   *
   * Once the analyser has been discarded, no references are retained to the input variables.
   *
   * @param xiSourceNet - the source propnet.
   * @param xiStateMachine - a state machine for use during analysis.
   */
  public LatchAnalyser(ForwardDeadReckonPropNet xiSourceNet,
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

    // Create the temporary list of complex positive goal latches.
    mComplexPositiveGoalLatchList = new ArrayList<>();

    mLatches = new Latches(mSourceNet, mStateMachine);

  }

  /**
   * Analyse a propnet for latches.
   *
   * @param xiDeadline - the (latest) time to run until.
   *
   * @return the results of the latch analysis.
   */
  public Latches analyse(long xiDeadline)
  {
    mDeadline = xiDeadline;
    try
    {
      analyse();
    }
    catch (TimeoutException lEx)
    {
      // Timed out whilst calculating latches.  Clear all the state.  (It's better to have none than for it to be
      // incomplete.)
      LOGGER.warn("Timed out whilst analysing latches");

      mLatches.mPositiveBaseLatches.clear();
      mLatches.mNegativeBaseLatches.clear();
      mLatches.mSimplePositiveGoalLatches.clear();
      mLatches.mSimpleNegativeGoalLatches.clear();
      mLatches.mComplexPositiveGoalLatches = new MaskedStateGoalLatch[0];
      mLatches.mFoundPositiveBaseLatches = false;
      mLatches.mFoundNegativeBaseLatches = false;
      mLatches.mFoundSimplePositiveGoalLatches = false;
      mLatches.mFoundSimpleNegativeGoalLatches = false;
      mLatches.mFoundComplexPositiveGoalLatches = false;
      mLatches.mAllRolesHavePositiveGoalLatches = false;
      mLatches.mPerRolePositiveGoalLatchMasks.clear();
      mLatches.mStaticGoalRanges.clear();
    }

    return mLatches;
  }

  private Latches analyse() throws TimeoutException
  {
    // Do per-proposition analysis on all the base propositions.
    for (PolymorphicComponent lSourceComp1 : mSourceNet.getBasePropositionsArray())
    {
      checkForTimeout();

      // Check if this proposition is a goal latch or a regular latch (or not a latch at all).
      tryLatch((ForwardDeadReckonProposition)lSourceComp1, true);
      tryLatch((ForwardDeadReckonProposition)lSourceComp1, false);
    }

    tryLatchPairs();

    postProcessLatches();

    return mLatches;
  }

  /**
   * Test whether a proposition is a latch, adding it to the set of latches if so.
   *
   * @param xiProposition - the proposition to test.
   * @param xiPositive - the latch sense.
   */
  private void tryLatch(ForwardDeadReckonProposition xiProposition, boolean xiPositive)
  {
    TristateProposition lTristateProposition = getProp(xiProposition);
    Tristate lTestState = xiPositive ? Tristate.TRUE : Tristate.FALSE;
    Tristate lOtherState = xiPositive ? Tristate.FALSE : Tristate.TRUE;
    Set<ForwardDeadReckonProposition> lLatchSet = xiPositive ? mLatches.mPositiveBaseLatches :
                                                               mLatches.mNegativeBaseLatches;

    try
    {
      mTristateNet.reset();
      lTristateProposition.assume(Tristate.UNKNOWN, lTestState, Tristate.UNKNOWN);
      if (lTristateProposition.getValue(2) == lTestState)
      {
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
          mLatches.mFoundSimplePositiveGoalLatches = true;
        }
        else if (lValue == Tristate.FALSE)
        {
          addLatchingProposition((ForwardDeadReckonProposition)lGoal, xiProposition, false);
          mLatches.mFoundSimpleNegativeGoalLatches = true;
        }
      }
    }
  }

  private void addLatchingProposition(ForwardDeadReckonProposition xiGoal,
                                      ForwardDeadReckonProposition xiLatchingProposition,
                                      boolean xiPositive)
  {
    Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lGoalMap =
                                                                           (xiPositive ? mLatches.mSimplePositiveGoalLatches :
                                                                                         mLatches.mSimpleNegativeGoalLatches);
    ForwardDeadReckonInternalMachineState lExisting = lGoalMap.get(xiGoal);
    lExisting.add(xiLatchingProposition.getInfo());
  }

  private void postProcessLatches()
  {
    // Post-process base latches into a state mask for fast access.
    for (ForwardDeadReckonProposition lProp : mLatches.mPositiveBaseLatches)
    {
      mLatches.mFoundPositiveBaseLatches = true;
      mLatches.mPositiveBaseLatchMask.add(lProp.getInfo());
    }

    for (ForwardDeadReckonProposition lProp : mLatches.mNegativeBaseLatches)
    {
      mLatches.mFoundNegativeBaseLatches = true;
      mLatches.mNegativeBaseLatchMask.add(lProp.getInfo());
    }

    // Post-process the goal latches to remove any goals for which no latches were found.
    Iterator<Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState>> lIterator =
      mLatches.mSimplePositiveGoalLatches.entrySet().iterator();
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

    if (mLatches.mSimplePositiveGoalLatches.isEmpty())
    {
      LOGGER.info("No simple positive goal latches");
      mLatches.mSimplePositiveGoalLatches = null;
    }

    lIterator = mLatches.mSimpleNegativeGoalLatches.entrySet().iterator();
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

    if (mLatches.mSimpleNegativeGoalLatches.isEmpty())
    {
      LOGGER.info("No simple negative goal latches");
      mLatches.mSimpleNegativeGoalLatches = null;
    }

    // On a per-role basis, calculate the state masks that imply some positively latched goal for the role.
    if (mLatches.mSimplePositiveGoalLatches != null)
    {
      // Assume that all roles have at least 1 positive latch until we learn otherwise.
      mLatches.mAllRolesHavePositiveGoalLatches = true;

      for (Role lRole : mStateMachine.getRoles())
      {
        ForwardDeadReckonInternalMachineState lRoleLatchMask = mStateMachine.createEmptyInternalState();

        for (PolymorphicProposition goalProp : mSourceNet.getGoalPropositions().get(lRole))
        {
          ForwardDeadReckonInternalMachineState goalMask = mLatches.mSimplePositiveGoalLatches.get(goalProp);

          if (goalMask != null)
          {
            lRoleLatchMask.merge(goalMask);
          }
        }

        if (lRoleLatchMask.size() > 0)
        {
          mLatches.mPerRolePositiveGoalLatchMasks.put(lRole, lRoleLatchMask);
        }
        else
        {
          mLatches.mAllRolesHavePositiveGoalLatches = false;
        }
      }
    }
    else
    {
      mLatches.mAllRolesHavePositiveGoalLatches = false;
    }

    LOGGER.info(mComplexPositiveGoalLatchList.size() + " complex positive goal latches");
    mLatches.mComplexPositiveGoalLatches =
      mComplexPositiveGoalLatchList.toArray(new MaskedStateGoalLatch[mComplexPositiveGoalLatchList.size()]);
    mLatches.mFoundComplexPositiveGoalLatches = (!mComplexPositiveGoalLatchList.isEmpty());
  }

  /**
   * Find pairs of base latches which make a goal latch.
   *
   * @throws TimeoutException
   */
  private void tryLatchPairs() throws TimeoutException
  {
    // This is expensive.  Only do it for puzzles.
    if (mStateMachine.getRoles().length != 1) return;

    // Only do it if we haven't found any goal latches so far.
    if (mLatches.mFoundSimplePositiveGoalLatches || mLatches.mFoundSimpleNegativeGoalLatches) return;

    // Only do it if there are fewer than 300 base latches.
    if (mLatches.mPositiveBaseLatches.size() > 300) return;

    // It's worth checking to see if any pairs of base proposition latches constitute a goal latch.  Many logic puzzles
    // contain constraints on pairs of propositions that might manifest in this way.
    //
    // Only consider positive base latches, simply because there aren't any games where we need to do this for negative
    // base latches.
    LOGGER.info("Checking for latch pairs of " + mLatches.mPositiveBaseLatches.size() + " 1-proposition latches");
    for (ForwardDeadReckonProposition lBaseLatch1 : mLatches.mPositiveBaseLatches)
    {
      checkForTimeout();

      // !! ARR Do the "assume" for the first state here and then save/reload as required.
      // !! ARR Don't do both 1/2 and 2/1.
      for (ForwardDeadReckonProposition lBaseLatch2 : mLatches.mPositiveBaseLatches)
      {
        try
        {
          mTristateNet.reset();
          // !! ARR Ideally only set up as a basic latch if it is a basic latch.
          getProp(lBaseLatch1).assume(Tristate.FALSE, Tristate.TRUE, Tristate.UNKNOWN);
          getProp(lBaseLatch2).assume(Tristate.FALSE, Tristate.TRUE, Tristate.UNKNOWN);
          checkGoalLatchPair(lBaseLatch1, lBaseLatch2);
        }
        catch (ContradictionException lEx) { /* Oops */ }
      }
    }
  }

  /**
   * Check whether any goals are latched by a pair of propositions.  If so, add the pair to the set of pair latches.
   *
   * @param xiProposition1 - the 1st proposition which MUST itself be a positive latch.
   * @param xiProposition1 - the 2ns proposition which MUST itself be a positive latch.
   */
  private void checkGoalLatchPair(ForwardDeadReckonProposition xiProposition1,
                                  ForwardDeadReckonProposition xiProposition2)
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
          LOGGER.debug(xiProposition1.getName() + " & " + xiProposition2.getName() + " are a +ve pair latch for " + lGoal.getName());

          MaskedStateGoalLatch lMaskedState =
                          new MaskedStateGoalLatch(mStateMachine, ((ForwardDeadReckonProposition)lGoal).getGoalValue());
          lMaskedState.add(xiProposition1, true);
          lMaskedState.add(xiProposition2, true);
          mComplexPositiveGoalLatchList.add(lMaskedState);
        }
        // We only care about +ve goal latches for now
      }
    }
  }

  private TristateProposition getProp(PolymorphicProposition xiSource)
  {
    return (TristateProposition)mSourceToTarget.get(xiSource);
  }

  private void checkForTimeout() throws TimeoutException
  {
    if (System.currentTimeMillis() > mDeadline)
    {
      throw new TimeoutException();
    }
  }
}