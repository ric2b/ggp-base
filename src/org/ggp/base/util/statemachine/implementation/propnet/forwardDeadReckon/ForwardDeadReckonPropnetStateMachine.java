
package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.RuntimeGameCharacteristics;
import org.ggp.base.player.gamer.statemachine.sancho.TreePath;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.factory.OptimizingPolymorphicPropNetFactory;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonComponent;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonComponentFactory;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState.InternalMachineStateIterator;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropnetFastAnimator;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;
import org.ggp.base.util.propnet.polymorphic.learning.LearningComponent;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.query.ProverQueryBuilder;
import org.ggp.base.util.stats.Stats;

/**
 * A state machine.
 *
 * This class is not thread-safe.  Each instance must be accessed by a single thread.
 */
public class ForwardDeadReckonPropnetStateMachine extends StateMachine
{
  private static final Logger LOGGER = LogManager.getLogger();

  /** The underlying proposition network - in various optimised forms. */

  // The complete propnet.
  private ForwardDeadReckonPropNet                                     fullPropNet                     = null;

  // A propnet containing just those components required to compute the goal values when it's already known that the
  // state is terminal.
  private ForwardDeadReckonPropNet                                     goalsNet                        = null;

  // The propnet is split into two networks dependent on the proposition which changes most frequently during metagame
  // simulations.  This is commonly a "control" proposition identifing which player's turn it is in a non-simultaneous
  // game.  The X-net contains just those components required when the control proposition is true.  The O-net contains
  // just those components required when the control proposition is false.
  //
  // In games without greedy rollouts, these are set to the goalless version (below).
  private ForwardDeadReckonPropNet                                     propNetX                        = null;
  private ForwardDeadReckonPropNet                                     propNetO                        = null;

  // X- and O-nets without the goal calculation logic (which is in goalsNet).
  private ForwardDeadReckonPropNet                                     propNetXWithoutGoals            = null;
  private ForwardDeadReckonPropNet                                     propNetOWithoutGoals            = null;

  // The propnet that is currently in use (one of the X- or O- nets).
  private ForwardDeadReckonPropNet                                     propNet                         = null;

  private Map<Role, ForwardDeadReckonComponent[]>                      legalPropositionsX              = null;
  private Map<Role, Move[]>                                            legalPropositionMovesX          = null;
  private Map<Role, ForwardDeadReckonComponent[]>                      legalPropositionsO              = null;
  private Map<Role, Move[]>                                            legalPropositionMovesO          = null;
  private Map<Role, ForwardDeadReckonComponent[]>                      legalPropositions               = null;
  /** The player roles */
  int                                                                  numRoles;
  private Role[]                                                       roles;
  private ForwardDeadReckonInternalMachineState                        lastInternalSetStateX           = null;
  private ForwardDeadReckonInternalMachineState                        lastInternalSetStateO           = null;
  private ForwardDeadReckonInternalMachineState                        lastInternalSetState            = null;
  private final boolean                                                useSampleOfKnownLegals          = false;
  private GdlSentence                                                  XSentence                       = null;
  private GdlSentence                                                  OSentence                       = null;
  private ForwardDeadReckonPropositionCrossReferenceInfo               XSentenceInfo                   = null;
  private ForwardDeadReckonPropositionCrossReferenceInfo               OSentenceInfo                   = null;
  private MachineState                                                 initialState                    = null;
  private ForwardDeadReckonProposition[]                               moveProps                       = null;
  private ForwardDeadReckonProposition[]                               previousMovePropsX              = null;
  private ForwardDeadReckonProposition[]                               previousMovePropsO              = null;
  private boolean                                                      measuringBasePropChanges        = false;
  private Map<ForwardDeadReckonPropositionCrossReferenceInfo, Integer> basePropChangeCounts            = new HashMap<>();
  private ForwardDeadReckonProposition[]                               chosenJointMoveProps            = null;
  private Move[]                                                       chosenMoves                     = null;
  private ForwardDeadReckonProposition[]                               previouslyChosenJointMovePropsX = null;
  private ForwardDeadReckonProposition[]                               previouslyChosenJointMovePropsO = null;
  private int[]                                                        previouslyChosenJointMovePropIdsX = null;
  private int[]                                                        previouslyChosenJointMovePropIdsO = null;
  private int[]                                                        latchedScoreRangeBuffer         = new int[2];
  private int[]                                                        parentLatchedScoreRangeBuffer   = new int[2];
  private ForwardDeadReckonPropositionCrossReferenceInfo[]             masterInfoSet                   = null;
  private ForwardDeadReckonLegalMoveInfo[]                             masterLegalMoveSet              = null;
  private StateMachine                                                 validationMachine               = null;
  private RoleOrdering                                                 roleOrdering                    = null;
  private MachineState                                                 validationState                 = null;
  private int                                                          instanceId;
  private int                                                          maxInstances;
  private long                                                         metagameTimeout                 = 20000;
  private int                                                          numInstances                    = 1;
  private final Role                                                   ourRole;
  private boolean                                                      isPseudoPuzzle                  = false;
  private Set<Factor>                                                  factors                         = null;
  private StateMachineFilter                                           searchFilter                    = null;
  private ForwardDeadReckonInternalMachineState                        mNonControlMask                 = null;
  public long                                                          totalNumGatesPropagated         = 0;
  public long                                                          totalNumPropagates              = 0;
  private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mPositiveGoalLatches      = null;
  private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mNegativeGoalLatches      = null;
  private final Map<Role,int[]>                                        mStaticGoalRanges               = new HashMap<>();
  private Set<PolymorphicProposition>                                  mPositiveBasePropLatches        = null;
  private Set<PolymorphicProposition>                                  mNegativeBasePropLatches        = null;
  private final Set<GdlSentence>                                       mFillerMoves                    = new HashSet<>();
  private GoalsCalculator                                              mGoalsCalculator                = null;
  private Map<Role,ForwardDeadReckonInternalMachineState>              mRoleUnionPositiveGoalLatches   = null;

  private final TerminalResultSet                                      mResultSet                      = new TerminalResultSet();
  // A re-usable iterator over the propositions in a machine state.
  private final InternalMachineStateIterator                           mStateIterator = new InternalMachineStateIterator();
  private final RuntimeGameCharacteristics                             mGameCharacteristics;

  //  In games with negative goal latches greedy rollouts treat state transitions that lower the opponent's
  //  maximum achievable score somewhat like transitions to winning terminal states, which is to say they
  //  preferentially select them, and preferentially avoid the converse of their own max score being
  //  reduced.  The next two parameters govern how strong that preference is (0 = pref -> 100 = always)
  //  Ideally these parameters will have natural values of 0 or 100 corresponding to the mechanism being
  //  turned on or off - anything in between implies another parameter to tune that is highly likely to be
  //  game dependent.  The most natural setting would be (100,100) which would be directly analogous with
  //  the handling of decisive win terminals, however, experimentation with ELB (the canonical game for multi-player
  //  with goal latches) shows that (100,0) works a bit better.  This feels wrong, because it is equivalent to
  //  saying (in ELB) that kings will always be captured when they can be during a rollout (fine), but nothing
  //  will be done to avoid putting a king in a position where it can be immediately captured (seems wrong).
  //  This empirical preference for (100,0) over (100,100) is something I am no entirely comfortable with, but
  //  until a counter example comes along we'll just live with (note (100,100) is still WAY better than before we had
  //  the mechanism at all, so if we had to go to that due to a counter example this is still a big step forward)
  private final int                                                    latchImprovementWeight = 100;
  private final int                                                    latchWorseningAvoidanceWeight = 0;

  private class TestPropnetStateMachineStats extends Stats
  {
    private long totalResets;
    private int  numStateSettings;
    private long totalGets;
    private int  numStateFetches;
    private int  numBaseProps;
    private int  numInputs;
    private int  numLegals;

    public TestPropnetStateMachineStats(int numBaseProps,
                                        int numInputs,
                                        int numLegals)
    {
      this.numBaseProps = numBaseProps;
      this.numInputs = numInputs;
      this.numLegals = numLegals;
    }

    @Override
    public void clear()
    {
      totalResets = 0;
      numStateSettings = 0;
      totalGets = 0;
      numStateFetches = 0;
    }

    @Override
    public String toString()
    {
      StringBuilder sb = new StringBuilder();

      sb.append("#base props: " + numBaseProps);
      sb.append("\n");
      sb.append("#inputs: " + numInputs);
      sb.append("\n");
      sb.append("#legals: " + numLegals);
      sb.append("\n");
      sb.append("#state sets: " + numStateSettings);
      sb.append("\n");
      if (numStateSettings > 0)
      {
        sb.append("Average #components reset per state set: " + totalResets /
                  numStateSettings);
        sb.append("\n");
      }
      sb.append("#state gets: " + numStateFetches);
      sb.append("\n");
      if (numStateFetches > 0)
      {
        sb.append("Average #components queried per state get: " + totalGets /
                  numStateFetches);
        sb.append("\n");
      }

      return sb.toString();
    }
  }

  public class MoveWeights
  {
    public double[]  weightScore;
    private int      numSamples = 1;
    private double   total      = 0;
    private int      weightSize;
    private double[] averageScores;

    public MoveWeights(int vectorSize, int numRoles)
    {
      weightSize = vectorSize;
      weightScore = new double[vectorSize];
      averageScores = new double[numRoles];
      clear();
    }

    public void clear()
    {
      total = weightSize * 50;
      numSamples = 1;

      for (int i = 0; i < weightSize; i++)
      {
        weightScore[i] = 50;
      }
    }

    public MoveWeights copy()
    {
      MoveWeights result = new MoveWeights(weightSize, averageScores.length);

      for (int i = 0; i < weightScore.length; i++)
      {
        result.weightScore[i] = weightScore[i];
      }

      result.numSamples = numSamples;
      result.total = total;

      return result;
    }

    public void addSample(double[] scores,
                          List<ForwardDeadReckonLegalMoveInfo> moves)
    {
      for (ForwardDeadReckonLegalMoveInfo move : moves)
      {
        double score = scores[move.roleIndex];

        double oldWeight = weightScore[move.masterIndex];
        double newWeigth = (oldWeight * numSamples + score) / (numSamples + 1);
        weightScore[move.masterIndex] = newWeigth;

        total += (newWeigth - oldWeight);
      }
      numSamples++;
    }

    public void addResult(double[] scores, ForwardDeadReckonLegalMoveInfo move)
    {
      double score = scores[move.roleIndex];

      double oldWeight = weightScore[move.masterIndex];
      double newWeigth = (oldWeight * numSamples + score) / (numSamples + 1);
      weightScore[move.masterIndex] = newWeigth;

      total += (newWeigth - oldWeight);
    }

    public void noteSampleComplete()
    {
      numSamples++;
    }

    public void accumulate(MoveWeights other)
    {
      total = 0;

      for (int i = 0; i < weightSize; i++)
      {
        weightScore[i] = (weightScore[i] * numSamples + other.weightScore[i] *
                                                        other.numSamples) /
                         (numSamples + other.numSamples);
        total += weightScore[i];
      }

      numSamples += other.numSamples;
    }

    public double getAverage()
    {
      return total / weightSize;
    }

    public double getStdDeviation()
    {
      double var = 0;
      double mean = total / weightSize;

      for (int i = 0; i < weightSize; i++)
      {
        var += (weightScore[i] - mean) * (weightScore[i] - mean);
      }

      return Math.sqrt(var / weightSize);
    }
  }

  private TestPropnetStateMachineStats stats;

  public Stats getStats()
  {
    return stats;
  }

  private ForwardDeadReckonInternalMachineState createInternalState(ForwardDeadReckonPropositionCrossReferenceInfo[] infoSet,
                                                                    GdlSentence XSentence,
                                                                    MachineState state)
  {
    //ProfileSection methodSection = new ProfileSection("InternalMachineState.constructFromMachineState");
    //try
    {
      ForwardDeadReckonInternalMachineState result = new ForwardDeadReckonInternalMachineState(infoSet);

      for (GdlSentence s : state.getContents())
      {
        ForwardDeadReckonProposition p = (ForwardDeadReckonProposition)propNet.getBasePropositions().get(s);
        ForwardDeadReckonPropositionInfo info = p.getInfo();
        result.add(info);

        result.isXState |= (info.sentence == XSentence);
      }

      LOGGER.trace("Created internal state: " + result + " with hash " + result.hashCode());
      return result;
    }
    //finally
    //{
    //	methodSection.exitScope();
    //}
  }

  public ForwardDeadReckonPropositionCrossReferenceInfo[] getInfoSet()
  {
    return masterInfoSet;
  }

  public ForwardDeadReckonInternalMachineState createInternalState(MachineState state)
  {
    return createInternalState(masterInfoSet, XSentence, state);
  }

  public RoleOrdering getRoleOrdering()
  {
    return roleOrdering;
  }

  private void setRoleOrdering(RoleOrdering xiRoleOrdering)
  {
    roleOrdering = xiRoleOrdering;
  }

  /**
   * @return whether the game may be treated as a puzzle (has no
   * dependence on any other role's moves)
   */
  public boolean getIsPseudoPuzzle()
  {
    return isPseudoPuzzle;
  }

  public void performSemanticAnalysis(long timeout)
  {
    findLatches(timeout);

    if (factors == null)
    {
      PartitionedChoiceAnalyser analyzer = new PartitionedChoiceAnalyser(this);
      //  If it did not factorize does it partition? (currently we do not support both
      //  at once).  Note that this analysis must be done after the propnet is crystallized
      StateMachineFilter partitionFilter = analyzer.generatePartitionedChoiceFilter();

      if (partitionFilter != null)
      {
        setBaseFilter(partitionFilter);
      }
    }
  }

  /**
   * Find latches.
   */
  // !! ARR Work in progress - will need to return something
  private void findLatches(long timeout)
  {
    // As a quick win for now, we'll keep a simple record of any propositions which latch a goal proposition (either
    // positively or negatively).
    mPositiveGoalLatches = new HashMap<>();
    mNegativeGoalLatches = new HashMap<>();
    mPositiveBasePropLatches = new HashSet<>();
    mNegativeBasePropLatches = new HashSet<>();

    for (PolymorphicProposition lGoals[] : fullPropNet.getGoalPropositions().values())
    {
      for (PolymorphicProposition lGoal : lGoals)
      {
        mPositiveGoalLatches.put(lGoal, new ForwardDeadReckonInternalMachineState(masterInfoSet));
        mNegativeGoalLatches.put(lGoal, new ForwardDeadReckonInternalMachineState(masterInfoSet));
      }
    }

    for (PolymorphicProposition lBaseProp : fullPropNet.getBasePropositionsArray())
    {
      if ( System.currentTimeMillis() > timeout )
      {
        return;
      }

      if (lBaseProp.getSingleInput() instanceof PolymorphicTransition)
      {
        // Assume that this base proposition is a positive latch and look for the consequences.
        Set<PolymorphicComponent> lPositivelyLatched = new HashSet<>();
        Set<PolymorphicComponent> lNegativelyLatched = new HashSet<>();
        findAllLatchedStatesFor(lBaseProp,
                                true,
                                (ForwardDeadReckonProposition)lBaseProp,
                                lPositivelyLatched,
                                lNegativelyLatched,
                                0);
        if (lPositivelyLatched.contains(lBaseProp))
        {
          mPositiveBasePropLatches.add(lBaseProp);
          LOGGER.debug("Latch(+ve): " + lBaseProp);

          // If we've just latched any goal props, remember it.
          for (Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry :
                                                                                        mPositiveGoalLatches.entrySet())
          {
            PolymorphicProposition lGoal = lEntry.getKey();
            if (lPositivelyLatched.contains(lGoal))
            {
              lEntry.getValue().add(((ForwardDeadReckonProposition)lBaseProp).getInfo());
            }
          }

          for (Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry :
                                                                                        mNegativeGoalLatches.entrySet())
          {
            PolymorphicProposition lGoal = lEntry.getKey();
            if (lNegativelyLatched.contains(lGoal))
            {
              lEntry.getValue().add(((ForwardDeadReckonProposition)lBaseProp).getInfo());
            }
          }
        }

        // Assume that this base proposition is a negative latch and look for the consequences.
        lPositivelyLatched = new HashSet<>();
        lNegativelyLatched = new HashSet<>();
        findAllLatchedStatesFor(lBaseProp,
                                false,
                                (ForwardDeadReckonProposition)lBaseProp,
                                lPositivelyLatched,
                                lNegativelyLatched,
                                0);
        if (lNegativelyLatched.contains(lBaseProp))
        {
          mNegativeBasePropLatches.add(lBaseProp);
          LOGGER.debug("Latch(-ve): " + lBaseProp);
        }
      }
    }

    // Post-process the goal latches to remove any goals for which no latches were found.
    Iterator<Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState>> lIterator =
                                                                             mPositiveGoalLatches.entrySet().iterator();
    while (lIterator.hasNext())
    {
      Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry = lIterator.next();
      PolymorphicProposition lGoal = lEntry.getKey();
      ForwardDeadReckonInternalMachineState lLatches = lEntry.getValue();

      if (lLatches.size() != 0)
      {
        LOGGER.info("Goal '" + lGoal + "' is positively latched by any of: " + lLatches);
      }
      else
      {
        lIterator.remove();
      }
    }

    if (mPositiveGoalLatches.isEmpty())
    {
      LOGGER.info("No positive goal latches");
      mPositiveGoalLatches = null;
    }

    lIterator = mNegativeGoalLatches.entrySet().iterator();
    while (lIterator.hasNext())
    {
      Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry = lIterator.next();
      PolymorphicProposition lGoal = lEntry.getKey();
      ForwardDeadReckonInternalMachineState lLatches = lEntry.getValue();

      if (lLatches.size() != 0)
      {
        LOGGER.info("Goal '" + lGoal + "' is negatively latched by any of: " + lLatches);
      }
      else
      {
        lIterator.remove();
      }
    }

    if (mNegativeGoalLatches.isEmpty())
    {
      LOGGER.info("No negative goal latches");
      mNegativeGoalLatches = null;
    }
  }

  private void findAllLatchedStatesFor(PolymorphicComponent xiComponent,
                                       boolean xiForcedOutputValue,
                                       ForwardDeadReckonProposition xiOriginal,
                                       Set<PolymorphicComponent> xiPositivelyLatched,
                                       Set<PolymorphicComponent> xiNegativelyLatched,
                                       int xiDepth)
  {
    // Check if we've already visited this component.  (This is expected, because we do latching through transitions.)
    if ((xiPositivelyLatched.contains(xiComponent)) || (xiNegativelyLatched.contains(xiComponent)))
    {
      return;
    }

    // Record the forced value of this component.
    //
    // If this is the original component for which we're trying to determine whether or not it's latched, only consider
    // it as latched if it's latched in the next turn.  Don't consider it to be latched at depth 0 (that will always be
    // the case - this system works by setting it true in turn 0 and seeing what happens next).  Don't consider it to be
    // a latch if it doesn't happen until the turn after the next turn.  That would, for example, consider a control
    // prop to be latched in a 2-player game.
    //
    // We're basically doing a proof by induction.  We need to show that, if a base prop is true (or false) at depth n
    // then it will be true (or false) at depth n+1.  That pretty much sums up a latch.
    assert(Collections.disjoint(xiPositivelyLatched, xiNegativelyLatched));
    boolean lConsiderAsLatched = ((xiComponent != xiOriginal) || (xiDepth == 1));
    if (lConsiderAsLatched)
    {
      if (xiForcedOutputValue)
      {
        xiPositivelyLatched.add(xiComponent);
      }
      else
      {
        xiNegativelyLatched.add(xiComponent);
      }
    }
    assert(Collections.disjoint(xiPositivelyLatched, xiNegativelyLatched));

    // Check which downstream components are latched as a result.
    for (PolymorphicComponent lComp : xiComponent.getOutputs())
    {
      if (lComp instanceof PolymorphicProposition)
      {
        findAllLatchedStatesFor(lComp,
                                xiForcedOutputValue,
                                xiOriginal,
                                xiPositivelyLatched,
                                xiNegativelyLatched,
                                xiDepth);

        // If we've just negatively latched a LEGAL prop, also negatively latch the corresponding DOES prop.
        if (!xiForcedOutputValue)
        {
          // Find the matching proposition in the legal/input map.  (If we don't have a LEGAL/DOES prop in hand then we
          // won't find anything.  Also, it can't be a DOES prop in hand because they can't ever have logic leading to
          // them.  So, if we do find a match, it's the DOES prop corresponding to the LEGAL prop in hand.)
          PolymorphicProposition lDoesProp = fullPropNet.getLegalInputMap().get(lComp);
          if (lDoesProp != null)
          {
            findAllLatchedStatesFor(lComp,
                                    xiForcedOutputValue,
                                    xiOriginal,
                                    xiPositivelyLatched,
                                    xiNegativelyLatched,
                                    xiDepth + 1);
          }
        }
      }
      else if (lComp instanceof PolymorphicOr)
      {
        boolean transmitsLatchInput = true;

        if ( !xiForcedOutputValue )
        {
          //  Handle one common special-case - where an OR is with the Init proposition, which
          //  can be assumed to be false for any next state calculation and thus for latch analysis
          for(PolymorphicComponent c : lComp.getInputs())
          {
            if ( c != xiComponent && c != fullPropNet.getInitProposition() )
            {
              transmitsLatchInput = false;
              break;
            }
          }
        }
        if ( transmitsLatchInput )
        {
          // This OR gate never has true inputs other than the one being considered, and
          //  so passes the latch value through
          findAllLatchedStatesFor(lComp,
                                  xiForcedOutputValue,
                                  xiOriginal,
                                  xiPositivelyLatched,
                                  xiNegativelyLatched,
                                  xiDepth);
        }
      }
      //  Note - no need to special-case Init for the negatively latch AND case since Init always
      //  manifests via an OR
      else if ((lComp instanceof PolymorphicAnd) && (!xiForcedOutputValue))
      {
        // This AND gate will always have a false input, therefore the output will always be false.
        findAllLatchedStatesFor(lComp,
                                xiForcedOutputValue,
                                xiOriginal,
                                xiPositivelyLatched,
                                xiNegativelyLatched,
                                xiDepth);
      }
      else if (lComp instanceof PolymorphicNot)
      {
        findAllLatchedStatesFor(lComp,
                                !xiForcedOutputValue,
                                xiOriginal,
                                xiPositivelyLatched,
                                xiNegativelyLatched,
                                xiDepth);
      }
      else if (lComp instanceof PolymorphicTransition)
      {
        findAllLatchedStatesFor(lComp,
                                xiForcedOutputValue,
                                xiOriginal,
                                xiPositivelyLatched,
                                xiNegativelyLatched,
                                xiDepth + 1);
      }
    }
  }

  /**
   * @return the latched score for the specified state, or null if there isn't one.
   *
   * @param xiState - the state.
   *
   * !! ARR 1P latches?
   */
//  public Integer getLatchedScore(ForwardDeadReckonInternalMachineState xiState)
//  {
//    if (mPositiveGoalLatches != null)
//    {
//      for (Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry : mPositiveGoalLatches.entrySet())
//      {
//        if (xiState.intersects(lEntry.getValue()))
//        {
//          return Integer.parseInt(lEntry.getKey().getName().getBody().get(1).toString());
//        }
//      }
//    }
//
//    return null;
//  }

  /**
   * @param xiState - state to test for latched score in
   * @return true if all roles' scores are latched
   */
  public boolean scoresAreLatched(ForwardDeadReckonInternalMachineState xiState)
  {
    if ( mGoalsCalculator != null )
    {
      if ( mGoalsCalculator.scoresAreLatched(xiState) )
      {
        return true;
      }
    }

    if (mPositiveGoalLatches != null)
    {
      if ( mRoleUnionPositiveGoalLatches == null )
      {
        //  Allocate a working buffer for future use and calculate the role masks that imply
        //  some positively latched goal for the role
        mRoleUnionPositiveGoalLatches = new HashMap<>();

        for(Role role : getRoles())
        {
          ForwardDeadReckonInternalMachineState roleLatchMask = new ForwardDeadReckonInternalMachineState(getInfoSet());

          for(PolymorphicProposition goalProp : fullPropNet.getGoalPropositions().get(role))
          {
            ForwardDeadReckonInternalMachineState goalMask = mPositiveGoalLatches.get(goalProp);

            if ( goalMask != null )
            {
              roleLatchMask.merge(goalMask);
            }
          }

          if ( roleLatchMask.size() > 0 )
          {
            mRoleUnionPositiveGoalLatches.put(role, roleLatchMask);
          }
        }
      }

      boolean result = true;

      for(Role role : getRoles())
      {
        ForwardDeadReckonInternalMachineState roleLatchMask = mRoleUnionPositiveGoalLatches.get(role);
        if ( roleLatchMask != null )
        {
          if ( !xiState.intersects(roleLatchMask) )
          {
            result = false;
            break;
          }
        }
        else
        {
          result = false;
          break;
        }
      }

      return result;
    }

    return false;
  }

  /**
   * Get the latched range of possible scores for a given role in a given state
   * @param xiState - the state
   * @param role - the role
   * @param range - array of length 2 to contain [min,max]
   */
  public void getLatchedScoreRange(ForwardDeadReckonInternalMachineState xiState, Role role, int[] range)
  {
    assert(range.length == 2);

    if ( mGoalsCalculator != null )
    {
      if ( mGoalsCalculator.scoresAreLatched(xiState))
      {
        range[0] = mGoalsCalculator.getGoalValue(xiState, role);
        range[1] = range[0];
        return;
      }
    }

    //  Initialize to sentinel values
    range[0] = Integer.MAX_VALUE;
    range[1] = -Integer.MAX_VALUE;
    int[] staticGoalRange = null;

    if ( mPositiveGoalLatches != null || mNegativeGoalLatches != null || (staticGoalRange = mStaticGoalRanges.get(role)) == null )
    {
      //  Initialize to sentinel values
      range[0] = Integer.MAX_VALUE;
      range[1] = -Integer.MAX_VALUE;

      for(PolymorphicProposition goalProp : fullPropNet.getGoalPropositions().get(role))
      {
        ForwardDeadReckonInternalMachineState negativeMask = null;
        int latchedScore = Integer.parseInt(goalProp.getName().getBody().get(1).toString());

        if ( mPositiveGoalLatches != null )
        {
          ForwardDeadReckonInternalMachineState positiveMask = mPositiveGoalLatches.get(goalProp);
          if (positiveMask != null && xiState.intersects(positiveMask))
          {
            range[0] = latchedScore;
            range[1] = latchedScore;
            break;
          }
        }
        if ( mNegativeGoalLatches != null )
        {
          negativeMask = mNegativeGoalLatches.get(goalProp);
        }
        if ( negativeMask == null || !xiState.intersects(negativeMask))
        {
          //  This is still a possible score
          if ( latchedScore < range[0] )
          {
            range[0] = latchedScore;
          }
          if ( latchedScore > range[1] )
          {
            range[1] = latchedScore;
          }
        }
      }

      if ( mPositiveGoalLatches == null && mNegativeGoalLatches == null )
      {
        staticGoalRange = new int[2];

        staticGoalRange[0] = range[0];
        staticGoalRange[1] = range[1];

        mStaticGoalRanges.put(role, staticGoalRange);
      }
    }
    else
    {
      range[0] = staticGoalRange[0];
      range[1] = staticGoalRange[1];
    }
  }

  /**
   * @return whether there are any negative goal latches.
   */
  public boolean hasNegativelyLatchedGoals()
  {
    return (mNegativeGoalLatches != null);
  }

  /**
   * @return whether there are any positive goal latches.
   */
  public boolean hasPositivelyLatchedGoals()
  {
    return (mPositiveGoalLatches != null);
  }

  /**
   * Query whether a specified proposition is known to be a positively latched base prop
   * @param p - proposition to check
   * @return true if it latches to true
   */
  public boolean isPositivelyLatchedBaseProp(PolymorphicProposition p)
  {
    return (mPositiveBasePropLatches != null && mPositiveBasePropLatches.contains(p));
  }

  /**
   * Get a mask of all positively latched base props
   * @return
   */
  public ForwardDeadReckonInternalMachineState getPositiveBaseLatches()
  {
    if ( mPositiveBasePropLatches == null )
    {
      return null;
    }

    ForwardDeadReckonInternalMachineState result = new ForwardDeadReckonInternalMachineState(masterInfoSet);

    for(PolymorphicProposition prop : mPositiveBasePropLatches)
    {
      result.add(((ForwardDeadReckonProposition)prop).getInfo());
    }

    return result;
  }

  /**
   * Get a mask of all negatively latched base props
   * @return
   */
  public ForwardDeadReckonInternalMachineState getNegativeBaseLatches()
  {
    if ( mNegativeBasePropLatches == null )
    {
      return null;
    }

    ForwardDeadReckonInternalMachineState result = new ForwardDeadReckonInternalMachineState(masterInfoSet);

    for(PolymorphicProposition prop : mNegativeBasePropLatches)
    {
      result.add(((ForwardDeadReckonProposition)prop).getInfo());
    }

    return result;
  }

  /**
   * Query whether a specified proposition is known to be a negatively latched base prop
   * @param p - proposition to check
   * @return true if it latches to false
   */
  public boolean isNegativelyLatchedBaseProp(PolymorphicProposition p)
  {
    return (mNegativeBasePropLatches != null && mNegativeBasePropLatches.contains(p));
  }

  /**
   * Get the average of all goals that aren't negatively latched, for each role, in the specified state.
   *
   * @param xiState  - the state.
   * @param xoValues - output array of values (one for each role).
   */
  public void getAverageAvailableGoals(ForwardDeadReckonInternalMachineState xiState,
                                       RoleOrdering xiRoleOrdering,
                                       double[] xoValues)
  {
    int[] lNumGoalValues = new int[xoValues.length];

    // By default, all goals are available.
    // !! ARR The output of this could be pre-computed.
    for (Entry<Role, PolymorphicProposition[]> lEntry : fullPropNet.getGoalPropositions().entrySet())
    {
      int lRoleIndex = xiRoleOrdering.roleToRoleIndex(lEntry.getKey());
      for (PolymorphicProposition lProp : lEntry.getValue())
      {
        ForwardDeadReckonProposition lGoalProp = (ForwardDeadReckonProposition)lProp;
        xoValues[lRoleIndex] += lGoalProp.getGoalValue();
        lNumGoalValues[lRoleIndex]++;
      }
    }

    if (mNegativeGoalLatches != null)
    {
      for (Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry : mNegativeGoalLatches.entrySet())
      {
        // Check if this goal is negatively latched.
        if (xiState.intersects(lEntry.getValue()))
        {
          // This goal is no longer available.
          ForwardDeadReckonProposition lGoalProp = (ForwardDeadReckonProposition)lEntry.getKey();
          LOGGER.debug("Goal not available: " + lGoalProp);
          int lRoleIndex = xiRoleOrdering.roleToRoleIndex(lGoalProp.getGoalRole());
          xoValues[lRoleIndex] -= lGoalProp.getGoalValue();
          lNumGoalValues[lRoleIndex]--;
        }
      }
    }

    for (int lii = 0; lii < xoValues.length; lii++)
    {
      assert(lNumGoalValues[lii] > 0) : "No goals remaining for " + xiRoleOrdering.roleIndexToRole(lii) +
                                        " in state " + xiState;
      xoValues[lii] /= lNumGoalValues[lii];
    }
  }

  public Set<MachineState> findTerminalStates(int maxResultSet, int maxDepth)
  {
    PolymorphicProposition terminal = fullPropNet.getTerminalProposition();

    return findSupportStates(terminal.getName(), maxResultSet, maxDepth);
  }

  public Set<MachineState> findGoalStates(Role role,
                                          int minValue,
                                          int maxResultSet,
                                          int maxDepth)
  {
    Set<MachineState> results = new HashSet<>();

    for (PolymorphicProposition p : fullPropNet.getGoalPropositions().get(role))
    {
      if (Integer.parseInt(p.getName().getBody().get(1).toString()) >= minValue)
      {
        results.addAll(findSupportStates(p.getName(), maxResultSet, maxDepth));
      }
    }

    return results;
  }

  private class AntecedantCursor
  {
    public Set<PolymorphicProposition> positiveProps;
    public Set<PolymorphicProposition> negativeProps;
    public boolean                     isPositive;

    public AntecedantCursor()
    {
      positiveProps = new HashSet<>();
      negativeProps = new HashSet<>();
      isPositive = true;
    }

    public AntecedantCursor(AntecedantCursor parent)
    {
      positiveProps = new HashSet<>(parent.positiveProps);
      negativeProps = new HashSet<>(parent.negativeProps);
      isPositive = parent.isPositive;
    }

    public boolean compatibleWith(AntecedantCursor other)
    {
      if (isPositive == other.isPositive)
      {
        for (PolymorphicProposition p : positiveProps)
        {
          if (other.negativeProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : other.positiveProps)
        {
          if (negativeProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : negativeProps)
        {
          if (other.positiveProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : other.negativeProps)
        {
          if (positiveProps.contains(p))
          {
            return false;
          }
        }
      }
      else
      {
        for (PolymorphicProposition p : positiveProps)
        {
          if (other.positiveProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : other.positiveProps)
        {
          if (positiveProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : negativeProps)
        {
          if (other.negativeProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : other.negativeProps)
        {
          if (negativeProps.contains(p))
          {
            return false;
          }
        }
      }

      return true;
    }

    public boolean compatibleWithAll(Set<AntecedantCursor> set)
    {
      for (AntecedantCursor c : set)
      {
        if (!compatibleWith(c))
        {
          return false;
        }
      }

      return true;
    }

    public void unionInto(AntecedantCursor c)
    {
      if (c.isPositive == isPositive)
      {
        c.positiveProps.addAll(positiveProps);
        c.negativeProps.addAll(negativeProps);
      }
      else
      {
        c.positiveProps.addAll(negativeProps);
        c.negativeProps.addAll(positiveProps);
      }
    }

    public void unionInto(Set<AntecedantCursor> set)
    {
      if (set.isEmpty())
      {
        set.add(this);
      }
      else
      {
        for (AntecedantCursor c : set)
        {
          unionInto(c);
        }
      }
    }
  }

  public void validateStateEquality(ForwardDeadReckonPropnetStateMachine other)
  {
    if (!lastInternalSetState.equals(other.lastInternalSetState))
    {
      LOGGER.warn("Last set state mismtch");
    }

    for (PolymorphicProposition p : propNet.getBasePropositionsArray())
    {
      ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;

      if (fdrp.getValue(instanceId) != fdrp.getValue(other.instanceId))
      {
        LOGGER.warn("Base prop state mismatch on: " + p);
      }
    }
  }

  private Set<AntecedantCursor> addPropositionAntecedants(PolymorphicPropNet pn,
                                                          PolymorphicComponent p,
                                                          AntecedantCursor cursor,
                                                          int maxResultSet,
                                                          int maxDepth,
                                                          int depth)
  {
    if (depth >= maxDepth)
    {
      return null;
    }

    if (p instanceof PolymorphicTransition)
    {
      return null;
    }
    else if (p instanceof PolymorphicProposition)
    {
      PolymorphicProposition prop = (PolymorphicProposition)p;

      if (pn.getBasePropositions().values().contains(prop))
      {
        AntecedantCursor newCursor = new AntecedantCursor(cursor);

        if (cursor.isPositive)
        {
          if (!cursor.negativeProps.contains(p))
          {
            newCursor.positiveProps.add(prop);
            Set<AntecedantCursor> result = new HashSet<>();
            result.add(newCursor);
            return result;
          }
          else if (!cursor.positiveProps.contains(p))
          {
            newCursor.negativeProps.add(prop);
            Set<AntecedantCursor> result = new HashSet<>();
            result.add(newCursor);
            return result;
          }
          else
          {
            return null;
          }
        }
        if (!cursor.positiveProps.contains(p))
        {
          newCursor.negativeProps.add(prop);
          Set<AntecedantCursor> result = new HashSet<>();
          result.add(newCursor);
          return result;
        }
        else if (!cursor.negativeProps.contains(p))
        {
          newCursor.positiveProps.add(prop);
          Set<AntecedantCursor> result = new HashSet<>();
          result.add(newCursor);
          return result;
        }
        else
        {
          return null;
        }
      }

      return addPropositionAntecedants(pn,
                                       p.getSingleInput(),
                                       cursor,
                                       maxResultSet,
                                       maxDepth,
                                       depth + 1);
    }
    else if (p instanceof PolymorphicNot)
    {
      cursor.isPositive = !cursor.isPositive;
      Set<AntecedantCursor> result = addPropositionAntecedants(pn,
                                                               p.getSingleInput(),
                                                               cursor,
                                                               maxResultSet,
                                                               maxDepth,
                                                               depth + 1);
      cursor.isPositive = !cursor.isPositive;

      return result;
    }
    else if (p instanceof PolymorphicAnd)
    {
      Set<AntecedantCursor> subResults = new HashSet<>();

      for (PolymorphicComponent c : p.getInputs())
      {
        if (subResults.size() > maxResultSet)
        {
          return null;
        }

        AntecedantCursor newCursor = new AntecedantCursor(cursor);
        Set<AntecedantCursor> inputResults = addPropositionAntecedants(pn,
                                                                       c,
                                                                       newCursor,
                                                                       maxResultSet,
                                                                       maxDepth,
                                                                       depth + 1);
        if (inputResults == null)
        {
          //	No positive matches in an AND that requires a positive result => failure
          if (cursor.isPositive)
          {
            return null;
          }
        }
        else
        {
          if (cursor.isPositive)
          {
            //	We require ALL inputs, so take the conditions gathered for this one and validate
            //	consistency with the current cursor, then add them into that condition set
            if (subResults.isEmpty())
            {
              subResults = inputResults;
            }
            else
            {
              Set<AntecedantCursor> validInputResults = new HashSet<>();

              for (AntecedantCursor cur : inputResults)
              {
                for (AntecedantCursor subResult : subResults)
                {
                  if (subResult.compatibleWith(cur))
                  {
                    AntecedantCursor combinedResult = new AntecedantCursor(subResult);
                    cur.unionInto(combinedResult);

                    validInputResults.add(combinedResult);
                  }
                }
              }

              subResults = validInputResults;
            }
          }
          else
          {
            //	This is a OR when viewed in the negative sense, so we just need one, and each such
            //	match is a new results set
            subResults.addAll(inputResults);
          }
        }
      }

      return subResults;
    }
    else if (p instanceof PolymorphicOr)
    {
      Set<AntecedantCursor> subResults = new HashSet<>();

      for (PolymorphicComponent c : p.getInputs())
      {
        if (subResults.size() > maxResultSet)
        {
          return null;
        }

        AntecedantCursor newCursor = new AntecedantCursor(cursor);
        Set<AntecedantCursor> inputResults = addPropositionAntecedants(pn,
                                                                       c,
                                                                       newCursor,
                                                                       maxResultSet,
                                                                       maxDepth,
                                                                       depth + 1);
        if (inputResults == null)
        {
          //	Any positive matches in an OR that requires a negative result => failure
          if (!cursor.isPositive)
          {
            return null;
          }
        }
        else
        {
          if (!cursor.isPositive)
          {
            //	We require ALL inputs to be negative, so take the conditions gathered for this one and validate
            //	consistency with the current cursor, then add them into that condition set
            if (subResults.isEmpty())
            {
              subResults = inputResults;
            }
            else
            {
              Set<AntecedantCursor> validInputResults = new HashSet<>();

              for (AntecedantCursor cur : inputResults)
              {
                for (AntecedantCursor subResult : subResults)
                {
                  if (subResult.compatibleWith(cur))
                  {
                    AntecedantCursor combinedResult = new AntecedantCursor(subResult);
                    cur.unionInto(combinedResult);

                    validInputResults.add(combinedResult);
                  }
                }
              }

              subResults = validInputResults;
            }
          }
          else
          {
            //	Any positive will do, and each such
            //	match is a new results set
            subResults.addAll(inputResults);
          }
        }
      }

      return subResults;
    }

    throw new RuntimeException("Unknown component");
  }

  public Set<MachineState> findSupportStates(GdlSentence queryProposition,
                                             int maxResultSet,
                                             int maxDepth)
  {
    Set<MachineState> result = new HashSet<>();

    PolymorphicProposition p = fullPropNet.findProposition(queryProposition);
    if (p != null)
    {
      Set<AntecedantCursor> cursorSet = addPropositionAntecedants(fullPropNet,
                                                                  p,
                                                                  new AntecedantCursor(),
                                                                  maxResultSet,
                                                                  maxDepth,
                                                                  0);

      if ( cursorSet != null )
      {
        for (AntecedantCursor c : cursorSet)
        {
          MachineState satisfyingState = new MachineState(new HashSet<GdlSentence>());

          for (PolymorphicProposition prop : c.positiveProps)
          {
            satisfyingState.getContents().add(prop.getName());
          }

          result.add(satisfyingState);
        }
      }
    }

    return result;
  }

  public ForwardDeadReckonPropnetStateMachine()
  {
    maxInstances = 1;
    ourRole = null;
    mGameCharacteristics = null;
  }

  public ForwardDeadReckonPropnetStateMachine(int xiMaxInstances,
                                              long xiMetaGameTimeout,
                                              Role xiOurRole,
                                              RuntimeGameCharacteristics xiGameCharacteristics)
  {
    maxInstances = xiMaxInstances;
    metagameTimeout = xiMetaGameTimeout;
    ourRole = xiOurRole;
    mGameCharacteristics = xiGameCharacteristics;
  }

  private ForwardDeadReckonPropnetStateMachine(ForwardDeadReckonPropnetStateMachine master, int instanceId)
  {
    maxInstances = -1;
    this.instanceId = instanceId;
    propNetX = master.propNetX;
    propNetO = master.propNetO;
    propNetXWithoutGoals = master.propNetXWithoutGoals;
    propNetOWithoutGoals = master.propNetOWithoutGoals;
    goalsNet = master.goalsNet;
    XSentence = master.XSentence;
    OSentence = master.OSentence;
    XSentenceInfo = master.XSentenceInfo;
    OSentenceInfo = master.OSentenceInfo;
    legalPropositionMovesX = master.legalPropositionMovesX;
    legalPropositionMovesO = master.legalPropositionMovesO;
    legalPropositionsX = master.legalPropositionsX;
    legalPropositionsO = master.legalPropositionsO;
    legalPropositions = master.legalPropositions;
    initialState = master.initialState;
    roles = master.roles;
    numRoles = master.numRoles;
    fullPropNet = master.fullPropNet;
    masterInfoSet = master.masterInfoSet;
    factors = master.factors;
    mPositiveGoalLatches = master.mPositiveGoalLatches;
    mNegativeGoalLatches = master.mNegativeGoalLatches;
    ourRole = master.ourRole;
    setRoleOrdering(master.getRoleOrdering());
    totalNumMoves = master.totalNumMoves;
    if ( master.mGoalsCalculator != null )
    {
      mGoalsCalculator = master.mGoalsCalculator.createThreadSafeReference();
    }
    mRoleUnionPositiveGoalLatches = master.mRoleUnionPositiveGoalLatches;
    mGameCharacteristics = master.mGameCharacteristics;

    stateBufferX1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    stateBufferX2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    stateBufferO1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    stateBufferO2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);

    for(int i = 0; i < rolloutDecisionStack.length; i++)
    {
      rolloutDecisionStack[i] = new RolloutDecisionState();
    }

    moveProps = new ForwardDeadReckonProposition[numRoles];
    previousMovePropsX = new ForwardDeadReckonProposition[numRoles];
    previousMovePropsO = new ForwardDeadReckonProposition[numRoles];
    chosenJointMoveProps = new ForwardDeadReckonProposition[numRoles];
    chosenMoves = new Move[numRoles];
    previouslyChosenJointMovePropsX = new ForwardDeadReckonProposition[numRoles];
    previouslyChosenJointMovePropsO = new ForwardDeadReckonProposition[numRoles];
    previouslyChosenJointMovePropIdsX = new int[numRoles];
    previouslyChosenJointMovePropIdsO = new int[numRoles];

    stats = new TestPropnetStateMachineStats(fullPropNet.getBasePropositions().size(),
                                             fullPropNet.getInputPropositions().size(),
                                             fullPropNet.getLegalPropositions().get(getRoles()[0]).length);
  }

  public ForwardDeadReckonPropnetStateMachine createInstance()
  {
    if (numInstances >= maxInstances)
    {
      throw new RuntimeException("Too many instances");
    }

    ForwardDeadReckonPropnetStateMachine result = new ForwardDeadReckonPropnetStateMachine(this, numInstances++);

    return result;
  }

  @Override
  public void initialize(List<Gdl> description)
  {
    // Log the GDL so that we can play again as required.
    StringBuffer lGDLString  = new StringBuffer();
    lGDLString.append("GDL\n");
    for (Gdl element : description)
    {
      lGDLString.append(element);
      lGDLString.append('\n');
    }
    LOGGER.debug(lGDLString.toString());

    setRandomSeed(1);

    try
    {
      //validationMachine = new ProverStateMachine();
      //validationMachine.initialize(description);

      fullPropNet = (ForwardDeadReckonPropNet)OptimizingPolymorphicPropNetFactory.create(
                                                                               description,
                                                                               new ForwardDeadReckonComponentFactory());
      fullPropNet.renderToFile("propnet_001.dot");

      OptimizingPolymorphicPropNetFactory.removeAnonymousPropositions(fullPropNet);
      fullPropNet.renderToFile("propnet_012_AnonRemoved.dot");
      LOGGER.debug("Num components after anon prop removal: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.removeUnreachableBasesAndInputs(fullPropNet);
      fullPropNet.renderToFile("propnet_014_UnreachablesRemoved.dot");

      isPseudoPuzzle = OptimizingPolymorphicPropNetFactory.removeIrrelevantBasesAndInputs(fullPropNet, ourRole, mFillerMoves);
      fullPropNet.renderToFile("propnet_016_IrrelevantRemoved.dot");
      LOGGER.debug("Num components after unreachable removal: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.removeRedundantConstantsAndGates(fullPropNet, false);
      fullPropNet.renderToFile("propnet_018_RedundantRemoved.dot");
      LOGGER.debug("Num components after first pass redundant components removal: " +
                   fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.refactorLargeGates(fullPropNet);
      fullPropNet.renderToFile("propnet_020_BeforeLargeFanout.dot");

      OptimizingPolymorphicPropNetFactory.refactorLargeFanouts(fullPropNet);
      fullPropNet.renderToFile("propnet_030_AfterLargeFanout.dot");
      LOGGER.debug("Num components after large gate refactoring: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.removeDuplicateLogic(fullPropNet);
      LOGGER.debug("Num components after duplicate removal: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.optimizeInputSets(fullPropNet);
      LOGGER.debug("Num components after input set optimization: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.optimizeInvertedInputs(fullPropNet);
      LOGGER.debug("Num components after inverted input optimization: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.removeRedundantConstantsAndGates(fullPropNet);
      LOGGER.debug("Num components after further removal of redundant components: " +
                   fullPropNet.getComponents().size());

      // Ensure that no propositions apart from strict input props (base, does, init) have any outputs, as this is
      // assumed by the fast animator.  Accordingly we re-wire slightly such that if any such do exist we replace their
      // output connection by one from their input (which they anyway just directly forward, so this also removes a
      // small propagation step).
      OptimizingPolymorphicPropNetFactory.removeNonBaseOrDoesPropositionOutputs(fullPropNet);

      fullPropNet.renderToFile("propnet_040_Reduced.dot");
      roles = fullPropNet.getRoles();
      numRoles = roles.length;
      roleOrdering = new RoleOrdering(this, ourRole);
      setRoleOrdering(roleOrdering);

      moveProps = new ForwardDeadReckonProposition[numRoles];
      previousMovePropsX = new ForwardDeadReckonProposition[numRoles];
      previousMovePropsO = new ForwardDeadReckonProposition[numRoles];
      chosenJointMoveProps = new ForwardDeadReckonProposition[numRoles];
      chosenMoves = new Move[numRoles];
      previouslyChosenJointMovePropsX = new ForwardDeadReckonProposition[numRoles];
      previouslyChosenJointMovePropsO = new ForwardDeadReckonProposition[numRoles];
      previouslyChosenJointMovePropIdsX = new int[numRoles];
      previouslyChosenJointMovePropIdsO = new int[numRoles];
      stats = new TestPropnetStateMachineStats(fullPropNet.getBasePropositions().size(),
                                               fullPropNet.getInputPropositions().size(),
                                               fullPropNet.getLegalPropositions().get(getRoles()[0]).length);
      //	Assess network statistics
      int numInputs = 0;
      int numMultiInputs = 0;
      int numMultiInputComponents = 0;

      for (PolymorphicComponent c : fullPropNet.getComponents())
      {
        int n = c.getInputs().size();

        numInputs += n;

        if (n > 1)
        {
          numMultiInputComponents++;
          numMultiInputs += n;
        }
      }

      int numComponents = fullPropNet.getComponents().size();
      LOGGER.debug("Num components: " + numComponents + " with an average of " + numInputs / numComponents + " inputs.");
      LOGGER.debug("Num multi-input components: " +
                   numMultiInputComponents +
                   " with an average of " +
                   (numMultiInputComponents == 0 ? "N/A" : numMultiInputs /
                                                           numMultiInputComponents) +
                   " inputs.");

      masterInfoSet = new ForwardDeadReckonPropositionCrossReferenceInfo[fullPropNet.getBasePropositions().size()];
      int index = 0;
      for (Entry<GdlSentence, PolymorphicProposition> e : fullPropNet.getBasePropositions().entrySet())
      {
        ForwardDeadReckonProposition prop = (ForwardDeadReckonProposition)e.getValue();
        ForwardDeadReckonPropositionCrossReferenceInfo info = new ForwardDeadReckonPropositionCrossReferenceInfo();

        info.sentence = e.getKey();
        info.xNetProp = prop;
        info.oNetProp = prop;
        info.goalsNetProp = prop;
        info.index = index;

        masterInfoSet[index++] = info;

        prop.setInfo(info);
        basePropChangeCounts.put(info, 0);
      }

      fullPropNet.crystalize(masterInfoSet, null, maxInstances);
      masterLegalMoveSet = fullPropNet.getMasterMoveList();

      // Try to factor the game.  But...
      // - Don't do it if we know there's only 1 factor.
      // - Allow no more than half the remaining time.
      // - Don't do it if we've previously timed out whilst factoring this game and we don't have at least 25% more time
      //   now.
      long factorizationAnalysisTimeout = (metagameTimeout - System.currentTimeMillis()) / 2;

      if ((mGameCharacteristics.getNumFactors() != 1) &&
          (factorizationAnalysisTimeout > mGameCharacteristics.getMaxFactorFailureTime() * 1.25))
      {
        FactorAnalyser factorAnalyser = new FactorAnalyser(this);
        factors = factorAnalyser.analyse(factorizationAnalysisTimeout, mGameCharacteristics);

        if (factors != null)
        {
          LOGGER.info("Game appears to factorize into " + factors.size() + " factors");
        }

        mNonControlMask = new ForwardDeadReckonInternalMachineState(masterInfoSet);

        if (factorAnalyser.getControlProps() != null)
        {
          for (PolymorphicProposition p : factorAnalyser.getControlProps())
          {
            ForwardDeadReckonPropositionInfo info = ((ForwardDeadReckonProposition)p).getInfo();

            mNonControlMask.add(info);
          }
        }

        mNonControlMask.invert();
      }
      else
      {
        LOGGER.info("Not attempting to factor this game.  Previous attempted showed " +
                    mGameCharacteristics.getNumFactors() + " factor(s) in " +
                    mGameCharacteristics.getMaxFactorFailureTime() + "ms.");
      }

      for (ForwardDeadReckonPropositionInfo info : masterInfoSet)
      {
        ForwardDeadReckonPropositionCrossReferenceInfo crInfo = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

        crInfo.xNetPropId = crInfo.xNetProp.id;
        crInfo.oNetPropId = crInfo.oNetProp.id;
      }

      stateBufferX1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferX2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferO1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferO2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);

      for(int i = 0; i < rolloutDecisionStack.length; i++)
      {
        rolloutDecisionStack[i] = new RolloutDecisionState();
      }

      fullPropNet.reset(false);
      if (fullPropNet.getInitProposition() != null)
      {
        fullPropNet.setProposition(0, (ForwardDeadReckonProposition)fullPropNet.getInitProposition(), true);
      }
      propNet = fullPropNet;
      initialState = getInternalStateFromBase(null).getMachineState();
      fullPropNet.reset(true);

      measuringBasePropChanges = true;

      try
      {
        for (int i = 0; i < 10; i++)
        {
          performDepthCharge(initialState, null);
        }
      }
      catch (TransitionDefinitionException e1)
      {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      }
      catch (MoveDefinitionException e1)
      {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      }
      measuringBasePropChanges = false;

      int highestCount = 0;
      for (Entry<ForwardDeadReckonPropositionCrossReferenceInfo, Integer> e : basePropChangeCounts.entrySet())
      {
        if (e.getValue() > highestCount)
        {
          highestCount = e.getValue();
          XSentence = e.getKey().sentence;
        }
      }

      basePropChangeCounts = null;
      lastInternalSetState = null;
      lastGoalState = null;
      nextGoalState = null;
      propNet = null;

      for(int i = 0; i < previousMovePropsO.length; i++)
      {
        previousMovePropsO[i] = null;
      }

      propNetX = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
      propNetO = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
      goalsNet = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
      propNetX.RemoveInits();
      propNetO.RemoveInits();

      if (XSentence != null)
      {
        LOGGER.info("Reducing with respect to XSentence: " + XSentence);
        OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX, XSentence, true);

        //	If the reduced net always transitions it's own hard-wired sentence into the opposite state
        //	it may be part of a pivot whereby control passes between alternating propositions.  Check this
        //	Do we turn something else on unconditionally?
        for (Entry<GdlSentence, PolymorphicProposition> e : propNetX.getBasePropositions().entrySet())
        {
          PolymorphicComponent input = e.getValue().getSingleInput();

          if (input instanceof PolymorphicTransition)
          {
            PolymorphicComponent driver = input.getSingleInput();

            if (driver instanceof PolymorphicConstant && driver.getValue())
            {
              //	Found a suitable candidate
              OSentence = e.getKey();
              break;
            }
          }
        }

        if (OSentence != null)
        {
          LOGGER.debug("Possible OSentence: " + OSentence);
          OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, OSentence, true);

          //	Does this one turn the original back on?
          PolymorphicProposition originalPropInSecondNet = propNetO.getBasePropositions().get(XSentence);
          if (originalPropInSecondNet != null)
          {
            PolymorphicComponent input = originalPropInSecondNet.getSingleInput();

            if (input instanceof PolymorphicTransition)
            {
              PolymorphicComponent driver = input.getSingleInput();

              if (!(driver instanceof PolymorphicConstant) || !driver.getValue())
              {
                //	Nope - doesn't work
                OSentence = null;
                LOGGER.debug("Fails to recover back-transition to " + XSentence);
              }
            }
          }

          if (OSentence != null)
          {
            //	So if we set the first net's trigger condition to off in the second net do we find
            //	the second net's own trigger is always off?
            OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, XSentence, false);

            PolymorphicProposition OSentenceInSecondNet = propNetO.getBasePropositions().get(OSentence);
            if (OSentenceInSecondNet != null)
            {
              PolymorphicComponent input = OSentenceInSecondNet.getSingleInput();

              if (input instanceof PolymorphicTransition)
              {
                PolymorphicComponent driver = input.getSingleInput();

                if (!(driver instanceof PolymorphicConstant) || driver.getValue())
                {
                  //	Nope - doesn't work
                  LOGGER.info("Fails to recover back-transition remove of " + OSentence);
                  OSentence = null;
                }

                //	Finally, if we set the OSentence off in the first net do we recover the fact that
                //	the XSentence always moves to off in transitions from the first net?
                if (OSentence != null)
                {
                  OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX, OSentence, false);

                  PolymorphicProposition XSentenceInFirstNet = propNetX.getBasePropositions().get(XSentence);
                  if (XSentenceInFirstNet != null)
                  {
                    input = XSentenceInFirstNet.getSingleInput();

                    if (input instanceof PolymorphicTransition)
                    {
                      driver = input.getSingleInput();

                      if (!(driver instanceof PolymorphicConstant) ||
                          driver.getValue())
                      {
                        //	Nope - doesn't work
                        LOGGER.debug("Fails to recover removal of " + XSentence);
                        OSentence = null;
                      }
                    }
                  }
                }
              }
            }
            else
            {
              OSentence = null;
            }
          }
        }

        if (OSentence == null)
        {
          LOGGER.debug("Reverting OSentence optimizations");
          //	Failed - best we can do is simply drive the XSentence to true in one network
          propNetX = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
          propNetO = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
          propNetX.RemoveInits();
          propNetO.RemoveInits();
          OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX, XSentence, true);
          OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, XSentence, false);
        }
        //OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, XSentence, false);
        propNetX.renderToFile("propnet_050_ReducedX.dot");
        propNetO.renderToFile("propnet_060_ReducedO.dot");
        LOGGER.debug("Num components remaining in X-net: " + propNetX.getComponents().size());
        LOGGER.debug("Num components remaining in O-net: " + propNetO.getComponents().size());
      }

      propNetXWithoutGoals = new ForwardDeadReckonPropNet(propNetX, new ForwardDeadReckonComponentFactory());
      propNetOWithoutGoals = new ForwardDeadReckonPropNet(propNetO, new ForwardDeadReckonComponentFactory());
      propNetXWithoutGoals.RemoveGoals();
      propNetOWithoutGoals.RemoveGoals();
      OptimizingPolymorphicPropNetFactory.minimizeNetwork(propNetXWithoutGoals);
      OptimizingPolymorphicPropNetFactory.minimizeNetwork(propNetOWithoutGoals);
      propNetXWithoutGoals.renderToFile("propnet_070_XWithoutGoals.dot");
      propNetOWithoutGoals.renderToFile("propnet_080_OWithoutGoals.dot");

      goalsNet.RemoveAllButGoals();
      goalsNet.renderToFile("propnet_090_GoalsReduced.dot");

      LOGGER.info("Num components in goal-less X-net: " + propNetXWithoutGoals.getComponents().size());
      LOGGER.info("Num components in goal-less O-net: " + propNetOWithoutGoals.getComponents().size());
      LOGGER.info("Num components in goal net:        " + goalsNet.getComponents().size());

      //masterInfoSet = new ForwardDeadReckonPropositionCrossReferenceInfo[fullPropNet
      //    .getBasePropositions().size()];
      //index = 0;
      //	Cross-reference the base propositions of the two networks
      for (Entry<GdlSentence, PolymorphicProposition> e : fullPropNet.getBasePropositions().entrySet())
      {
        ForwardDeadReckonProposition oProp = (ForwardDeadReckonProposition)propNetO
            .getBasePropositions().get(e.getKey());
        ForwardDeadReckonProposition xProp = (ForwardDeadReckonProposition)propNetX
            .getBasePropositions().get(e.getKey());
        ForwardDeadReckonProposition goalsProp = (ForwardDeadReckonProposition)goalsNet
            .getBasePropositions().get(e.getKey());
        ForwardDeadReckonProposition fullNetPropFdr = (ForwardDeadReckonProposition)e.getValue();
        ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fullNetPropFdr.getInfo();
        //ForwardDeadReckonPropositionCrossReferenceInfo info = new ForwardDeadReckonPropositionCrossReferenceInfo();

        //info.sentence = e.getKey();
        info.xNetProp = xProp;
        info.oNetProp = oProp;
        info.goalsNetProp = goalsProp;
        //info.index = index;

        //masterInfoSet[index++] = info;

        xProp.setInfo(info);
        oProp.setInfo(info);
        if (goalsProp != null)
        {
          goalsProp.setInfo(info);
        }

        if (e.getKey().equals(XSentence))
        {
          XSentenceInfo = info;
        }
        else if (e.getKey().equals(OSentence))
        {
          OSentenceInfo = info;
        }
      }

      propNetX.crystalize(masterInfoSet, masterLegalMoveSet, maxInstances);
      propNetO.crystalize(masterInfoSet, masterLegalMoveSet, maxInstances);
      goalsNet.crystalize(masterInfoSet, masterLegalMoveSet, maxInstances);

      for(ForwardDeadReckonPropositionInfo info : masterInfoSet)
      {
        ForwardDeadReckonPropositionCrossReferenceInfo crInfo = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

        crInfo.xNetPropId = crInfo.xNetProp.id;
        crInfo.oNetPropId = crInfo.oNetProp.id;
      }

      goalsNet.reset(true);
      //	Force calculation of the goal set while we're single threaded
      goalsNet.getGoalPropositions();

      //  Set move factor info
      if ( factors != null )
      {
        //  Moves with no dependencies (typically a noop) can appear in multiple factors, but
        //  should be tagged as factor-free
        setMoveInfoForPropnet(propNetX);
        setMoveInfoForPropnet(propNetO);
      }

//      stateBufferX1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
//      stateBufferX2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
//      stateBufferO1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
//      stateBufferO2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);

      propNetX.reset(true);
      propNetO.reset(true);
      //	Force calculation of the goal set while we're single threaded
      propNetX.getGoalPropositions();
      propNetO.getGoalPropositions();

      propNet = propNetX;
      legalPropositions = legalPropositionsX;

      totalNumMoves = fullPropNet.getMasterMoveList().length;
    }
    catch (InterruptedException e)
    {
      // TODO: handle exception
    }
  }

  private void setMoveInfoForPropnet(ForwardDeadReckonPropNet pn)
  {
    //  Moves with no dependencies (typically a noop) can appear in multiple factors, but
    //  should be tagged as factor-free
    Set<ForwardDeadReckonLegalMoveInfo> multiFactorMoves = new HashSet<>();

    ForwardDeadReckonLegalMoveInfo[] factoredIndexMoveList = fullPropNet.getMasterMoveList();
    for(ForwardDeadReckonLegalMoveInfo info : pn.getMasterMoveList())
    {
      if ( info != null )
      {
        if ( factors != null )
        {
          for(Factor factor : factors)
          {
            if ( factor.getMoveInfos().contains(factoredIndexMoveList[info.masterIndex]))
            {
              if ( info.factor != null )
              {
                multiFactorMoves.add(info);
              }
              info.factor = factor;
            }
          }
        }

        if ( info.inputProposition != null && mFillerMoves.contains(info.inputProposition.getName()))
        {
          info.isVirtualNoOp = true;
        }
      }
    }

    if ( factors != null )
    {
      for(ForwardDeadReckonLegalMoveInfo info : multiFactorMoves)
      {
        info.factor = null;
      }
    }
  }

  public void Optimize()
  {
    for (PolymorphicComponent c : propNet.getComponents())
    {
      ((LearningComponent)c).Optimize();
    }
  }

  /**
   * Retrieve a reference to the underlying full propnet
   * @return the underlying full propnet
   */
  public ForwardDeadReckonPropNet getFullPropNet()
  {
    return fullPropNet;
  }

  /**
   * Return a search filter for use with this state machine when performing higher level goal search
   * @return filter to use
   */
  public StateMachineFilter getBaseFilter()
  {
    if (searchFilter == null)
    {
      searchFilter = new NullStateMachineFilter(this);
    }

    return searchFilter;
  }

  private void setBaseFilter(StateMachineFilter filter)
  {
    searchFilter = filter;
  }

  /**
   * Get a state mask for the non-control propositions
   * @return null if unknown else state mask
   */
  public ForwardDeadReckonInternalMachineState getNonControlMask()
  {
    return mNonControlMask;
  }

  private void setBasePropositionsFromState(MachineState state)
  {
    setBasePropositionsFromState(createInternalState(masterInfoSet,
                                                     XSentence,
                                                     state),
                                 false);
  }

  private ForwardDeadReckonInternalMachineState stateBufferX1 = null;
  private ForwardDeadReckonInternalMachineState stateBufferX2 = null;
  private ForwardDeadReckonInternalMachineState stateBufferO1 = null;
  private ForwardDeadReckonInternalMachineState stateBufferO2 = null;

  private void setBasePropositionsFromState(ForwardDeadReckonInternalMachineState state, boolean isolate)
  {
    InternalMachineStateIterator lIterator = mStateIterator ;

    if (lastInternalSetState != null)
    {
      if (!lastInternalSetState.equals(state))
      {
        ForwardDeadReckonInternalMachineState nextInternalSetState;

        lastInternalSetState.xor(state);
        if (isolate)
        {
          if (propNet == propNetX)
          {
            nextInternalSetState = (lastInternalSetState == stateBufferX1 ? stateBufferX2 : stateBufferX1);
          }
          else
          {
            nextInternalSetState = (lastInternalSetState == stateBufferO1 ? stateBufferO2 : stateBufferO1);
          }
          //nextInternalSetState = new ForwardDeadReckonInternalMachineState(state);
          nextInternalSetState.copy(state);
        }
        else
        {
          nextInternalSetState = state;
        }

        ForwardDeadReckonPropnetFastAnimator.InstanceInfo instanceInfo;

        if (!measuringBasePropChanges)
        {
          instanceInfo = propNet.animator.getInstanceInfo(instanceId);

          if ( propNet == propNetX)
          {
            lIterator.reset(lastInternalSetState);
            while (lIterator.hasNext())
            {
              ForwardDeadReckonPropositionInfo info = lIterator.next();
              ForwardDeadReckonPropositionCrossReferenceInfo infoCr = (ForwardDeadReckonPropositionCrossReferenceInfo)info;
              propNet.animator.changeComponentValueTo(instanceInfo, infoCr.xNetProp.id, nextInternalSetState.contains(info));
            }
          }
          else
          {
            lIterator.reset(lastInternalSetState);
            while (lIterator.hasNext())
            {
              ForwardDeadReckonPropositionInfo info = lIterator.next();
              ForwardDeadReckonPropositionCrossReferenceInfo infoCr = (ForwardDeadReckonPropositionCrossReferenceInfo)info;
              propNet.animator.changeComponentValueTo(instanceInfo, infoCr.oNetProp.id, nextInternalSetState.contains(info));
            }
          }
        }
        else
        {
          lIterator.reset(lastInternalSetState);
          while (lIterator.hasNext())
          {
            ForwardDeadReckonPropositionInfo info = lIterator.next();
            ForwardDeadReckonPropositionCrossReferenceInfo infoCr = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

            //if ( factor == null || factor.getStateMask().contains(infoCr))
            {
              if (nextInternalSetState.contains(info))
              {
                if (propNet == propNetX)
                {
                  propNet.animator.setComponentValue(instanceId, infoCr.xNetProp.id, true);
                }
                else
                {
                  propNet.animator.setComponentValue(instanceId, infoCr.oNetProp.id, true);
                }
              }
              else
              {
                if (propNet == propNetX)
                {
                  propNet.animator.setComponentValue(instanceId, infoCr.xNetProp.id, false);
                }
                else
                {
                  propNet.animator.setComponentValue(instanceId, infoCr.oNetProp.id, false);
                }
              }
            }

            if (measuringBasePropChanges)
            {
              basePropChangeCounts.put(infoCr,
                                       basePropChangeCounts.get(infoCr) + 1);
            }
          }
        }

        lastInternalSetState = nextInternalSetState;
      }
    }
    else
    {
      if (isolate)
      {
        lastInternalSetState = new ForwardDeadReckonInternalMachineState(state);
      }
      else
      {
        lastInternalSetState = state;
      }

      ForwardDeadReckonPropnetFastAnimator.InstanceInfo instanceInfo = propNet.animator.getInstanceInfo(instanceId);

      for (PolymorphicProposition p : propNet.getBasePropositionsArray())
      {
        propNet.animator.setComponentValue(instanceId, ((ForwardDeadReckonProposition)p).id, false);
      }

      lIterator.reset(state);
      while (lIterator.hasNext())
      {
        ForwardDeadReckonPropositionInfo s = lIterator.next();
        ForwardDeadReckonPropositionCrossReferenceInfo sCr = (ForwardDeadReckonPropositionCrossReferenceInfo)s;
        if (propNet == propNetX)
        {
          propNet.animator.changeComponentValueTo(instanceInfo, sCr.xNetProp.id, true);
        }
        else
        {
          propNet.animator.changeComponentValueTo(instanceInfo, sCr.oNetProp.id, true);
        }
      }
    }
  }

  /**
   * Computes if the state is terminal. Should return the value of the terminal
   * proposition for the state.
   */
  @Override
  public boolean isTerminal(MachineState state)
  {
    setPropNetUsage(state);
    setBasePropositionsFromState(state);

    PolymorphicProposition terminalProp = propNet.getTerminalProposition();
    //boolean result = propNet.getTransition(instanceId, (ForwardDeadReckonComponent)terminalProp.getSingleInput());
    boolean result = propNet.getComponentValue(instanceId, (ForwardDeadReckonComponent)terminalProp);

    return result;
  }

  public boolean isTerminal(ForwardDeadReckonInternalMachineState state)
  {
    setPropNetUsage(state);
    setBasePropositionsFromState(state, true);

    return isTerminal();
  }

  public boolean isTerminal()
  {
    if ( factors != null && !hasAvailableMoveForAllRoles() )
    {
      return true;
    }

    return isTerminalUnfactored();
  }

  private boolean isTerminalUnfactored()
  {
    PolymorphicProposition terminalProp = propNet.getTerminalProposition();
    boolean result = propNet.getComponentValue(instanceId, (ForwardDeadReckonComponent)terminalProp);

    if (validationMachine != null)
    {
      if (validationMachine.isTerminal(validationState) != result)
      {
        LOGGER.warn("Terminality mismatch");
      }
    }
    return result;
  }

  /**
   * Computes the goal for a role in the current state. Should return the value
   * of the goal proposition that is true for that role. If there is not
   * exactly one goal proposition true for that role, then you should throw a
   * GoalDefinitionException because the goal is ill-defined.
   */
  @Override
  public int getGoal(MachineState state, Role role)
  {
    ForwardDeadReckonInternalMachineState internalState = createInternalState(state);
    return getGoal(internalState, role);
  }

  public int getGoal(Role role)
  {
    return getGoal((ForwardDeadReckonInternalMachineState)null, role);
  }

  /**
   * Returns the initial state. The initial state can be computed by only
   * setting the truth value of the INIT proposition to true, and then
   * computing the resulting state.
   */
  @Override
  public MachineState getInitialState()
  {
    LOGGER.trace("Initial state: " + initialState);
    return initialState;
  }

  /**
   * Computes the legal moves for role in state.
   */
  @Override
  public List<Move> getLegalMoves(MachineState state, Role role)
  {
    List<Move> result;

    setPropNetUsage(state);
    setBasePropositionsFromState(state);

    //ForwardDeadReckonComponent.numGatesPropagated = 0;
    //ForwardDeadReckonComponent.numPropagates = 0;
    //propNet.seq++;

    result = new LinkedList<>();
    for (ForwardDeadReckonLegalMoveInfo moveInfo : propNet.getActiveLegalProps(instanceId).getContents(role))
    {
      result.add(moveInfo.move);
    }

    //totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
    //totalNumPropagates += ForwardDeadReckonComponent.numPropagates;

    return result;
  }

  public List<Move> getLegalMovesCopy(ForwardDeadReckonInternalMachineState state, Role role)
  {
    List<Move> result;

    setPropNetUsage(state);
    setBasePropositionsFromState(state, true);

    //ForwardDeadReckonComponent.numGatesPropagated = 0;
    //ForwardDeadReckonComponent.numPropagates = 0;
    //propNet.seq++;

    result = new LinkedList<>();
    for (ForwardDeadReckonLegalMoveInfo moveInfo : propNet.getActiveLegalProps(instanceId).getContents(role))
    {
      result.add(moveInfo.move);
    }

    //totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
    //totalNumPropagates += ForwardDeadReckonComponent.numPropagates;

    return result;
  }

  /**
   * @return the legal moves in the specified state for the specified role.
   *
   * @param state - the state.
   * @param role  - the role.
   *
   * WARNING: This version of the function returns a collection backed by a pre-allocated array.  It is only suitable
   *          for immediate use and not to be stored.
   */
  public Collection<ForwardDeadReckonLegalMoveInfo> getLegalMoves(ForwardDeadReckonInternalMachineState state,
                                                                  Role role)
  {
    setPropNetUsage(state);
    setBasePropositionsFromState(state, true);
    Collection<ForwardDeadReckonLegalMoveInfo> lResult = propNet.getActiveLegalProps(instanceId).getContents(role);
    assert(lResult.size() > 0);
    return lResult;
  }

  /**
   * @return the legal moves in the specified state as a ForwardDeadReckonLegalMoveSet.
   *
   * @param state - the state.
    *
   * WARNING: This version of the function returns a collection backed by a pre-allocated array.  It is only suitable
   *          for immediate use and not to be stored.
   */
  public ForwardDeadReckonLegalMoveSet getLegalMoveSet(ForwardDeadReckonInternalMachineState state)
  {
    setPropNetUsage(state);
    setBasePropositionsFromState(state, true);
     return propNet.getActiveLegalProps(instanceId);
  }

  /**
   * @return whether a specified move is legal for a role in a state.
   *
   * @param state - the state.
   * @param role  - the role.
   * @param move  - the proposed move.
   *
   * @throws MoveDefinitionException if the GDL is malformed.
   */
  public boolean isLegalMove(MachineState state, Role role, Move move) throws MoveDefinitionException
  {
    setPropNetUsage(state);
    setBasePropositionsFromState(state);

    Map<GdlSentence, PolymorphicProposition> inputProps = propNet.getInputPropositions();

    GdlSentence moveSentence = ProverQueryBuilder.toDoes(role, move);
    PolymorphicProposition moveInputProposition = inputProps.get(moveSentence);
    PolymorphicProposition legalProp = propNet.getLegalInputMap().get(moveInputProposition);
    if (legalProp != null)
    {
      return ((ForwardDeadReckonComponent)legalProp.getSingleInput()).getValue(instanceId);
    }

    throw new MoveDefinitionException(state, role);
  }

  private void setPropNetUsage(MachineState state)
  {
    setPropNetUsage(createInternalState(masterInfoSet, XSentence, state));
  }

  private void setPropNetUsage(ForwardDeadReckonInternalMachineState state)
  {
    if (XSentence != null)
    {
      if (state.isXState)
      {
        if (propNet != propNetX)
        {
          propNet = propNetX;
          legalPropositions = legalPropositionsX;

          lastInternalSetStateO = lastInternalSetState;
          lastInternalSetState = lastInternalSetStateX;
        }
      }
      else
      {
        if (propNet != propNetO)
        {
          propNet = propNetO;
          legalPropositions = legalPropositionsO;

          lastInternalSetStateX = lastInternalSetState;
          lastInternalSetState = lastInternalSetStateO;
        }
      }
    }
  }

  /**
   * Computes the next state given state and the list of moves.
   */
  @Override
  public MachineState getNextState(MachineState state, List<Move> moves)
  {
    //RuntimeOptimizedComponent.getCount = 0;
    //RuntimeOptimizedComponent.dirtyCount = 0;
    ForwardDeadReckonInternalMachineState internalState = createInternalState(masterInfoSet, XSentence, state);

    setPropNetUsage(internalState);

    ForwardDeadReckonInternalMachineState internalResult = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    ForwardDeadReckonLegalMoveInfo[] internalMoves = new ForwardDeadReckonLegalMoveInfo[moves.size()];

    Map<GdlSentence, PolymorphicProposition> inputProps = propNet.getInputPropositions();
    Map<PolymorphicProposition, PolymorphicProposition> legalInputMap = propNet.getLegalInputMap();
    int moveRawIndex = 0;

    for (GdlSentence moveSentence : toDoes(moves))
    {
      ForwardDeadReckonProposition moveInputProposition = (ForwardDeadReckonProposition)inputProps.get(moveSentence);
      ForwardDeadReckonLegalMoveInfo moveInfo;

      if (moveInputProposition != null)
      {
        ForwardDeadReckonProposition legalProp = (ForwardDeadReckonProposition)legalInputMap.get(moveInputProposition);

        moveInfo = propNet.getMasterMoveList()[legalProp.getInfo().index];
      }
      else
      {
        moveInfo = new ForwardDeadReckonLegalMoveInfo();

        moveInfo.isPseudoNoOp = true;
      }

      int internalMoveIndex = (roleOrdering == null ? moveRawIndex : roleOrdering.rawRoleIndexToRoleIndex(moveRawIndex));
      internalMoves[internalMoveIndex] = moveInfo;
      moveRawIndex++;
    }

    getNextState(internalState, null, internalMoves, internalResult);

    MachineState result = getInternalStateFromBase(null).getMachineState();

    return result;
  }

  /**
   * Get the next state given the current state and a set of moves.  Write the resulting state directly into the
   * supplied new state buffer.
   *
   * @param state       - the original state.
   * @param factor      - the factor.
   * @param moves       - the moves to make from the original state - in INTERNAL ordering
   * @param xbNewState  - the buffer into which the new state is written.
   */
  public void getNextState(ForwardDeadReckonInternalMachineState state,
                           Factor factor,
                           ForwardDeadReckonLegalMoveInfo[] moves,
                           ForwardDeadReckonInternalMachineState xbNewState)
  {
    assert(xbNewState != null);
    xbNewState.clear();

    setPropNetUsage(state);

    int movesCount = 0;
    int nonNullMovesCount = 0;

    for (ForwardDeadReckonLegalMoveInfo move : moves)
    {
      ForwardDeadReckonProposition moveProp = move.isPseudoNoOp ? null : move.inputProposition;
      moveProps[movesCount++] = moveProp;
      if ( moveProp != null )
      {
        nonNullMovesCount++;
      }
    }

    setBasePropositionsFromState(state, true);

    for (int i = 0; i < movesCount; i++)
    {
      ForwardDeadReckonProposition moveProp =  moveProps[i];
      ForwardDeadReckonProposition previousMoveProp = (propNet == propNetX ? previousMovePropsX[i] : previousMovePropsO[i]);

      if ( previousMoveProp != moveProp )
      {
        if ( propNet == propNetX )
        {
          previousMovePropsX[i] = moveProps[i];
        }
        else
        {
          previousMovePropsO[i] = moveProps[i];
        }

        if ( moveProp != null )
        {
          propNet.setProposition(instanceId, moveProp, true);
        }
        if ( previousMoveProp != null  )
        {
          propNet.setProposition(instanceId, previousMoveProp, false);
        }
      }
    }

    getInternalStateFromBase(xbNewState);

    if ( nonNullMovesCount == 0 && factor != null )
    {
      //  Hack - re-impose the base props from the starting state.  We need to do it this
      //  way in order for the non-factor turn logic (control prop, step, etc) to generate
      //  correctly, but then make sure we have not changed any factor-specific base props
      //  which can happen because no moves were played (consider distinct clauses on moves)
      ForwardDeadReckonInternalMachineState basePropState = new ForwardDeadReckonInternalMachineState(state);

      basePropState.intersect(factor.getStateMask(true));
      xbNewState.intersect(factor.getInverseStateMask(true));
      xbNewState.merge(basePropState);
    }
  }

  private boolean transitionToNextStateFromChosenMove()
  {
    //RuntimeOptimizedComponent.getCount = 0;
    //RuntimeOptimizedComponent.dirtyCount = 0;
    if (validationMachine != null)
    {
      List<Move> moves = new LinkedList<>();

      for (Move move : chosenMoves)
      {
        moves.add(move);
      }
      try
      {
        validationMachine.getNextState(validationState, moves);
      }
      catch (TransitionDefinitionException e)
      {
        e.printStackTrace();
      }
    }

    int index = 0;
    for (ForwardDeadReckonProposition moveProp : chosenJointMoveProps)
    {
      ForwardDeadReckonProposition previousChosenMove;
      int previousChosenMoveId;

      if (propNet == propNetX)
      {
        previousChosenMoveId = previouslyChosenJointMovePropIdsX[index];
      }
      else
      {
        previousChosenMoveId = previouslyChosenJointMovePropIdsO[index];
      }

      int movePropId = -1;
      if (moveProp != null)
      {
        movePropId = moveProp.id;
        propNet.animator.setComponentValue(instanceId, movePropId, true);
      }

      if (previousChosenMoveId != -1 && previousChosenMoveId != movePropId)
      {
        propNet.animator.setComponentValue(instanceId, previousChosenMoveId, false);
      }
      if (propNet == propNetX)
      {
        previouslyChosenJointMovePropIdsX[index++] = movePropId;
      }
      else
      {
        previouslyChosenJointMovePropIdsO[index++] = movePropId;
      }
    }

    propagateCalculatedNextState();

    return true;
  }

  /* Already implemented for you */
  @Override
  public Role[] getRoles()
  {
    return roles;
  }

  /* Helper methods */

  /**
   * The Input propositions are indexed by (does ?player ?action). This
   * translates a list of Moves (backed by a sentence that is simply ?action)
   * into GdlSentences that can be used to get Propositions from
   * inputPropositions. and accordingly set their values etc. This is a naive
   * implementation when coupled with setting input values, feel free to change
   * this for a more efficient implementation.
   *
   * @param moves
   * @return
   */
  private List<GdlSentence> toDoes(Move[] moves)
  {
    List<GdlSentence> doeses = new ArrayList<>(moves.length);
    Map<Role, Integer> roleIndices = getRoleIndices();

    for (Role lRole : roles)
    {
      int index = roleIndices.get(lRole);
      doeses.add(ProverQueryBuilder.toDoes(lRole, moves[index]));
    }
    return doeses;
  }

  /**
   * The Input propositions are indexed by (does ?player ?action). This
   * translates a list of Moves (backed by a sentence that is simply ?action)
   * into GdlSentences that can be used to get Propositions from
   * inputPropositions. and accordingly set their values etc. This is a naive
   * implementation when coupled with setting input values, feel free to change
   * this for a more efficient implementation.
   *
   * @param moves
   * @return
   */
  private List<GdlSentence> toDoes(List<Move> moves)
  {
    List<GdlSentence> doeses = new ArrayList<>(moves.size());
    Map<Role, Integer> roleIndices = getRoleIndices();

    for (Role lRole : roles)
    {
      int index = roleIndices.get(lRole);
      doeses.add(ProverQueryBuilder.toDoes(lRole, moves.get(index)));
    }
    return doeses;
  }

  private void propagateCalculatedNextState()
  {
    ForwardDeadReckonInternalMachineState transitionTo = propNet.getActiveBaseProps(instanceId);

    boolean targetIsXNet = transitionTo.contains(XSentenceInfo);
    if (propNet == propNetX)
    {
      if (!targetIsXNet)
      {
        propNet = propNetO;
        lastInternalSetStateX = lastInternalSetState;
        lastInternalSetState = lastInternalSetStateO;

        legalPropositions = legalPropositionsO;
      }
    }
    else
    {
      if (targetIsXNet)
      {
        propNet = propNetX;
        lastInternalSetStateO = lastInternalSetState;
        lastInternalSetState = lastInternalSetStateX;

        legalPropositions = legalPropositionsX;
      }
    }

    transitionTo.isXState = targetIsXNet;

    setBasePropositionsFromState(transitionTo, true);
  }

  private ForwardDeadReckonInternalMachineState getInternalStateFromBase(ForwardDeadReckonInternalMachineState xbState)
  {
    // Allocate a new state if we haven't been supplied with one to override.
    if (xbState == null)
    {
      xbState = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    }

    InternalMachineStateIterator lIterator = mStateIterator;
    lIterator.reset(propNet.getActiveBaseProps(instanceId));
    while (lIterator.hasNext())
    {
      ForwardDeadReckonPropositionInfo info = lIterator.next();
      xbState.add(info);

      if (info.sentence == XSentence)
      {
        xbState.isXState = true;
      }
    }

    return xbState;
  }

  private Map<Role, List<Move>> recentLegalMoveSetsList = new HashMap<>();

  @Override
  public Move getRandomMove(MachineState state, Role role)
      throws MoveDefinitionException
  {
    if (useSampleOfKnownLegals)
    {
      int choiceSeed = getRandom(100);
      final int tryPreviousPercentage = 80;
      List<Move> previouslyAvailableMoves = null;
      boolean preferNew = false;

      if (choiceSeed < tryPreviousPercentage &&
          recentLegalMoveSetsList.keySet().contains(role))
      {
        previouslyAvailableMoves = recentLegalMoveSetsList.get(role);
        Move result = previouslyAvailableMoves.get(getRandom(previouslyAvailableMoves.size()));

        if (isLegalMove(state, role, result))
        {
          return result;
        }
      }
      else if (choiceSeed > 100 - tryPreviousPercentage / 2)
      {
        preferNew = true;
      }

      List<Move> legals = getLegalMoves(state, role);
      List<Move> candidates;

      if (preferNew && previouslyAvailableMoves != null)
      {
        candidates = new LinkedList<>();

        for (Move move : legals)
        {
          if (!previouslyAvailableMoves.contains(move))
          {
            candidates.add(move);
          }
        }
      }
      else
      {
        candidates = legals;
      }

      if (legals.size() > 1)
      {
        recentLegalMoveSetsList.put(role, legals);
      }

      return candidates.get(getRandom(candidates.size()));
    }
    List<Move> legals = getLegalMoves(state, role);

    int randIndex = getRandom(legals.size());
    return legals.get(randIndex);
  }

  private class RolloutDecisionState
  {
    public RolloutDecisionState()
    {
      // TODO Auto-generated constructor stub
    }

    //  The following arrays may be null or point to a pre-allocated buffers
    public ForwardDeadReckonLegalMoveInfo[] chooserMoves;
    public boolean[]                        propProcessed;
    public int                              numChoices;
    //  The following are pre-allocated buffers used repeatedly to avoid GC
    //  It is expanded as necessary but never shrunk
    private ForwardDeadReckonLegalMoveInfo[] chooserMovesBuffer;
    private boolean[]                       propProcessedBuffer;
    public final ForwardDeadReckonProposition[]   nonChooserProps = new ForwardDeadReckonProposition[numRoles];
    public int                              chooserIndex;
    public int                              baseChoiceIndex;
    public int                              nextChoiceIndex;
    public int                              rolloutSeq;
    public int                              maxAchievableOpponentScoreTotal;
    final ForwardDeadReckonInternalMachineState   state = new ForwardDeadReckonInternalMachineState(getInfoSet());
    Role                                    choosingRole;

    void  clearMoveChoices()
    {
      numChoices = -1;
      chooserMoves = null;
      propProcessed = null;
    }

    void  setNumMoveChoices(int numMoveChoices)
    {
      if ( chooserMovesBuffer == null || chooserMovesBuffer.length < numMoveChoices )
      {
        //  Allow extra so we don't have to repeatedly expand
        chooserMovesBuffer = new ForwardDeadReckonLegalMoveInfo[numMoveChoices*2];
        propProcessedBuffer = new boolean[numMoveChoices*2];
      }

      numChoices = numMoveChoices;
      chooserMoves = chooserMovesBuffer;
      propProcessed = propProcessedBuffer;
    }
  }

  private final RolloutDecisionState[] rolloutDecisionStack = new RolloutDecisionState[TreePath.MAX_PATH_LEN];
  private int                    rolloutStackDepth;
  private int                    rolloutSeq           = 0;

  private int                    totalRoleoutChoices;
  private int                    totalRoleoutNodesExamined;

  private void doRecursiveGreedyRoleout(TerminalResultSet results,
                                        Factor factor,
                                        MoveWeights moveWeights,
                                        List<ForwardDeadReckonLegalMoveInfo> playedMoves,
                                        int cutoffDepth)
  {
    //		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.doRecursiveGreedyRoleout");
    //		try
    //		{
    ForwardDeadReckonProposition hintMoveProp = null;
    int hintMoveDepth = -1;

    do
    {
      assert(playedMoves == null || playedMoves.size() == rolloutStackDepth);
      ForwardDeadReckonProposition winningMoveProp = transitionToNextStateInGreedyRollout(results,
                                                                                          factor,
                                                                                          hintMoveProp,
                                                                                          moveWeights,
                                                                                          playedMoves);
      if (winningMoveProp != null)
      {
        hintMoveProp = winningMoveProp;
        hintMoveDepth = rolloutStackDepth;

        //	Next player had a 1 move forced win.  Pop the stack and choose again at this level unless deciding player was
        //	the same as for this node
        //	TODO - this doesn't handle well games in which the same player gets to play multiple times
        if (rolloutStackDepth > 0 &&
            rolloutDecisionStack[rolloutStackDepth - 1].nextChoiceIndex != rolloutDecisionStack[rolloutStackDepth - 1].baseChoiceIndex)
        {
          if (rolloutDecisionStack[rolloutStackDepth].chooserIndex != rolloutDecisionStack[rolloutStackDepth - 1].chooserIndex)
          {
            rolloutDecisionStack[rolloutStackDepth].chooserMoves = null;

            RolloutDecisionState poppedState = rolloutDecisionStack[--rolloutStackDepth];
            if (playedMoves != null)
            {
              playedMoves.remove(playedMoves.size() - 1);
            }

            setPropNetUsage(poppedState.state);
            setBasePropositionsFromState(poppedState.state, true);
          }
          else
          {
            if (!isTerminal() && !scoresAreLatched(lastInternalSetState))
            {
              if (rolloutStackDepth++ >= hintMoveDepth)
              {
                hintMoveProp = null;
              }
            }
            else
            {
              results.considerResult(rolloutDecisionStack[rolloutStackDepth].choosingRole);
              break;
            }
          }
        }
        else
        {
          if (!isTerminal() && !scoresAreLatched(lastInternalSetState))
          {
            if (rolloutStackDepth++ >= hintMoveDepth)
            {
              hintMoveProp = null;
            }
          }
          else
          {
            results.considerResult(rolloutDecisionStack[rolloutStackDepth].choosingRole);
            break;
          }
        }
      }
      else if (!isTerminal() && !scoresAreLatched(lastInternalSetState))
      {
        if (rolloutStackDepth++ >= hintMoveDepth)
        {
          hintMoveProp = null;
        }
      }
      else if (rolloutDecisionStack[rolloutStackDepth].nextChoiceIndex != rolloutDecisionStack[rolloutStackDepth].baseChoiceIndex)
      {
        //	Having recorded the potential terminal state continue to explore another
        //	branch given that this terminality was not a forced win for the deciding player
        RolloutDecisionState decisionState = rolloutDecisionStack[rolloutStackDepth];

        if (playedMoves != null)
        {
          playedMoves.remove(playedMoves.size() - 1);
        }

        setPropNetUsage(decisionState.state);
        setBasePropositionsFromState(decisionState.state, true);
      }
      else
      {
        break;
      }
    }
    while (cutoffDepth > rolloutStackDepth);
    //		}
    //		finally
    //		{
    //			methodSection.exitScope();
    //		}
  }

  private double recursiveGreedyRollout(TerminalResultSet results,
                                        Factor factor,
                                        MoveWeights moveWeights,
                                        List<ForwardDeadReckonLegalMoveInfo> playedMoves,
                                        int cutoffDepth)
  {
    rolloutSeq++;
    rolloutStackDepth = 0;
    totalRoleoutChoices = 0;
    totalRoleoutNodesExamined = 0;

    doRecursiveGreedyRoleout(results, factor, moveWeights, playedMoves, cutoffDepth);

    if (totalRoleoutNodesExamined > 0)
    {
      return totalRoleoutChoices / totalRoleoutNodesExamined;
    }
    return 0;
  }

  private String mungedState(ForwardDeadReckonInternalMachineState state)
  {
    StringBuilder sb = new StringBuilder();

    for (GdlSentence s : state.getMachineState().getContents())
    {
      if (s.toString().contains("wn"))
      {
        sb.append(s.toString());
      }
    }

    return sb.toString();
  }

  private Set<ForwardDeadReckonProposition> terminatingMoveProps             = new HashSet<>();
  public long                               numRolloutDecisionNodeExpansions = 0;
  public double                             greedyRolloutEffectiveness       = 0;
  private int                               terminalCheckHorizon             = 500; //  Effectively infinite by default

  public int getNumTerminatingMoveProps()
  {
    return terminatingMoveProps.size();
  }

  public void clearTerminatingMoveProps()
  {
    terminatingMoveProps.clear();
  }

  public void setTerminalCheckHorizon(int horizon)
  {
    terminalCheckHorizon = horizon;
  }

  private ForwardDeadReckonProposition transitionToNextStateInGreedyRollout(TerminalResultSet results,
                                                                            Factor factor,
                                                                            ForwardDeadReckonProposition hintMoveProp,
                                                                            MoveWeights moveWeights,
                                                                            List<ForwardDeadReckonLegalMoveInfo> playedMoves)
  {
    //		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.transitionToNextStateInGreedyRollout");
    //		try
    //		{
    ForwardDeadReckonLegalMoveSet activeLegalMoves = propNet.getActiveLegalProps(instanceId);
    int index = 0;
    boolean simultaneousMove = false;
    int maxChoices = 0;
    ForwardDeadReckonLegalMoveInfo choicelessMoveInfo = null;

    RolloutDecisionState decisionState = rolloutDecisionStack[rolloutStackDepth];
    if (decisionState.rolloutSeq != rolloutSeq)
    {
      decisionState.rolloutSeq = rolloutSeq;
      decisionState.clearMoveChoices();
    }

    if (decisionState.chooserMoves == null)
    {
      decisionState.choosingRole = null;
      decisionState.chooserIndex = -1;
      decisionState.baseChoiceIndex = -1;
      decisionState.nextChoiceIndex = -1;
      decisionState.maxAchievableOpponentScoreTotal = -1;
      totalRoleoutNodesExamined++;

      for (Role role : getRoles())
      {
        ForwardDeadReckonLegalMoveSet moves = activeLegalMoves;
        int numChoices = StateMachineFilterUtils.getFilteredSize(null, moves, role, factor, false);

        if (numChoices > maxChoices)
        {
          maxChoices = numChoices;
        }

        if (numChoices > 1)
        {
          totalRoleoutChoices += numChoices;
          if (decisionState.choosingRole == null)
          {
            if (!simultaneousMove)
            {
              decisionState.choosingRole = role;
              decisionState.setNumMoveChoices(numChoices);
            }
          }
          else
          {
            int rand = getRandom(decisionState.numChoices);

            ForwardDeadReckonLegalMoveInfo info = decisionState.chooserMoves[rand];
            decisionState.nonChooserProps[decisionState.chooserIndex] = info.inputProposition;
            if (playedMoves != null)
            {
              playedMoves.add(info);
            }

            decisionState.choosingRole = null;
            decisionState.clearMoveChoices();
            simultaneousMove = true;
          }
        }

        if (simultaneousMove)
        {
          int rand = getRandom(numChoices);

          Iterator<ForwardDeadReckonLegalMoveInfo> itr = moves.getContents(role).iterator();
          for (int iMove = 0; iMove < numChoices; iMove++)
          {
            // Get next move for this factor
            ForwardDeadReckonLegalMoveInfo info = StateMachineFilterUtils.nextFilteredMove(factor, itr);

            if (rand-- <= 0)
            {
              decisionState.nonChooserProps[index++] = info.inputProposition;
              //chosenJointMoveProps[index++] = info.inputProposition;
              if (playedMoves != null)
              {
                playedMoves.add(info);
              }
              break;
            }
          }
        }
        else
        {
          int chooserMoveIndex = 0;
          Iterator<ForwardDeadReckonLegalMoveInfo> itr = moves.getContents(role).iterator();
          for (int iMove = 0; iMove < numChoices; iMove++)
          {
            // Get next move for this factor
            ForwardDeadReckonLegalMoveInfo info = StateMachineFilterUtils.nextFilteredMove(factor, itr);

            if (decisionState.choosingRole == role)
            {
              if (chooserMoveIndex == 0)
              {
                decisionState.chooserIndex = index++;
              }
              decisionState.chooserMoves[chooserMoveIndex++] = info;
            }
            else
            {
              decisionState.nonChooserProps[index++] = info.inputProposition;
              if (info.inputProposition != null || choicelessMoveInfo == null)
              {
                choicelessMoveInfo = info;
              }
              break;
            }
          }
        }
      }
    }

    if (simultaneousMove)
    {
      for(int i = 0; i < chosenJointMoveProps.length; i++)
      {
        chosenJointMoveProps[i] = decisionState.nonChooserProps[i];
      }

      transitionToNextStateFromChosenMove();

      if (isTerminal() || scoresAreLatched(lastInternalSetState))
      {
        results.considerResult(null);
      }
    }
    else if (decisionState.chooserIndex != -1)
    {
      int choiceIndex;
      boolean preEnumerate = hasNegativelyLatchedGoals();
      int numTerminals = 0;

      if (decisionState.baseChoiceIndex == -1)
      {
        double total = 0;

        decisionState.state.copy(lastInternalSetState);
        decisionState.maxAchievableOpponentScoreTotal = 0;

        for(Role role : getRoles())
        {
          if ( !role.equals(decisionState.choosingRole))
          {
            getLatchedScoreRange(decisionState.state, role, latchedScoreRangeBuffer);

            decisionState.maxAchievableOpponentScoreTotal += latchedScoreRangeBuffer[1];
          }
        }

        for (int i = 0; i < decisionState.numChoices; i++)
        {
          ForwardDeadReckonLegalMoveInfo chooserMove = decisionState.chooserMoves[i];
          if (moveWeights != null)
          {
            total += moveWeights.weightScore[chooserMove.masterIndex];
          }
          if (!preEnumerate && terminatingMoveProps.contains(chooserMove.inputProposition))
          {
            preEnumerate = true;
            numRolloutDecisionNodeExpansions++;
            if (moveWeights == null)
            {
              break;
            }
          }
        }

        if (moveWeights == null)
        {
          decisionState.baseChoiceIndex = getRandom(decisionState.numChoices);
        }
        else
        {
          total = getRandom((int)total);
        }

        for (int i = 0; i < decisionState.numChoices; i++)
        {
          decisionState.propProcessed[i] = false;
          if (decisionState.baseChoiceIndex == -1)
          {
            total -= moveWeights.weightScore[decisionState.chooserMoves[i].masterIndex];
            if (total <= 0)
            {
              decisionState.baseChoiceIndex = i;
            }
          }
        }

        choiceIndex = decisionState.baseChoiceIndex;
      }
      else
      {
        choiceIndex = decisionState.nextChoiceIndex;
      }

      for (int roleIndex = 0; roleIndex < getRoles().length; roleIndex++)
      {
        if (roleIndex != decisionState.chooserIndex)
        {
          chosenJointMoveProps[roleIndex] = decisionState.nonChooserProps[roleIndex];
        }
      }

      boolean transitioned = false;

      getLatchedScoreRange(lastInternalSetState, decisionState.choosingRole, parentLatchedScoreRangeBuffer);

      //	If we're given a hint move to check for a win do that first
      //	the first time we look at this node
      if (hintMoveProp != null && decisionState.numChoices > 1)
      {
        if (decisionState.baseChoiceIndex == choiceIndex)
        {
          for (int i = 0; i < decisionState.numChoices; i++)
          {
            if (decisionState.chooserMoves[i].inputProposition == hintMoveProp)
            {
              chosenJointMoveProps[decisionState.chooserIndex] = decisionState.chooserMoves[i].inputProposition;

              transitionToNextStateFromChosenMove();

              if (isTerminal() || scoresAreLatched(lastInternalSetState))
              {
                numTerminals++;

                if (getGoal(decisionState.choosingRole) == parentLatchedScoreRangeBuffer[1])
                {
                  if (playedMoves != null)
                  {
                    playedMoves.add(decisionState.chooserMoves[i]);
                  }
                  greedyRolloutEffectiveness++;
                  //	If we have a choosable win stop searching
                  return hintMoveProp;
                }

                results.considerResult(decisionState.choosingRole);

                decisionState.propProcessed[i] = true;
              }
              else if ( hasNegativelyLatchedGoals() )
              {
                int newMaxAchievableOpponentScoreTotal = 0;
                for(Role role : getRoles())
                {
                  if ( !role.equals(decisionState.choosingRole))
                  {
                    getLatchedScoreRange(lastInternalSetState, role, latchedScoreRangeBuffer);

                    newMaxAchievableOpponentScoreTotal += latchedScoreRangeBuffer[1];
                  }
                }

                if ( newMaxAchievableOpponentScoreTotal < decisionState.maxAchievableOpponentScoreTotal )
                {
                  if ( getRandom(100) < latchImprovementWeight )
                  {
                    decisionState.nextChoiceIndex = decisionState.baseChoiceIndex;
                    if ( getRandom(100) < latchWorseningAvoidanceWeight )
                    {
                      return hintMoveProp;
                    }
                    return null;
                  }
                }
              }

              transitioned = true;
              break;
            }
          }
        }
        else
        {
          //	Not the first time we've looked at this node
          hintMoveProp = null;
        }
      }

      //	First time we visit the node try them all.  After that if we're asked to reconsider
      //	just take the next one from the last one we chose
      int remainingMoves = (decisionState.baseChoiceIndex == choiceIndex &&
                            preEnumerate ? decisionState.numChoices
                                        : 1);
      int choice = -1;
      int lastTransitionChoice = -1;

      for (int i = remainingMoves-1; i >= 0; i--)
      {
        choice = (i + choiceIndex) % decisionState.numChoices;

        //	Don't re-process the hint move that we looked at first unless this is the specific requested
        //  move
        if ( i > 0 )
        {
          if (decisionState.propProcessed[choice] ||
              hintMoveProp == decisionState.chooserMoves[choice].inputProposition ||
              (preEnumerate && !terminatingMoveProps.contains(decisionState.chooserMoves[choice].inputProposition)))
          {
            continue;
          }
        }

        if (transitioned)
        {
          setPropNetUsage(decisionState.state);
          setBasePropositionsFromState(decisionState.state, true);
        }

        chosenJointMoveProps[decisionState.chooserIndex] = decisionState.chooserMoves[choice].inputProposition;
        lastTransitionChoice = choice;

        transitionToNextStateFromChosenMove();

        transitioned = true;

        if (isTerminal() || scoresAreLatched(lastInternalSetState))
        {
          numTerminals++;

          if ( rolloutStackDepth <= terminalCheckHorizon )
          {
            terminatingMoveProps
                .add(decisionState.chooserMoves[choice].inputProposition);
          }

          if (getGoal(decisionState.choosingRole) == parentLatchedScoreRangeBuffer[1])
          {
            if (playedMoves != null)
            {
              playedMoves.add(decisionState.chooserMoves[choice]);
            }
            if (preEnumerate)
            {
              greedyRolloutEffectiveness++;
            }

            //	If we have a choosable win stop searching
            return decisionState.chooserMoves[choice].inputProposition;
          }

          results.considerResult(decisionState.choosingRole);
          decisionState.propProcessed[choice] = true;
        }
        else if ( hasNegativelyLatchedGoals() )
        {
          int newMaxAchievableOpponentScoreTotal = 0;
          for(Role role : getRoles())
          {
            if ( !role.equals(decisionState.choosingRole))
            {
              getLatchedScoreRange(lastInternalSetState, role, latchedScoreRangeBuffer);

              newMaxAchievableOpponentScoreTotal += latchedScoreRangeBuffer[1];
            }
          }

          if ( newMaxAchievableOpponentScoreTotal < decisionState.maxAchievableOpponentScoreTotal )
          {
            if ( getRandom(100) < latchImprovementWeight )
            {
              decisionState.nextChoiceIndex = (choiceIndex+1)%decisionState.numChoices;
              if ( getRandom(100) < latchWorseningAvoidanceWeight )
              {
                return decisionState.chooserMoves[choice].inputProposition;
              }
              return null;
            }
          }
        }
      }

      if ( !transitioned )
      {
        chosenJointMoveProps[decisionState.chooserIndex] = decisionState.chooserMoves[choice].inputProposition;
        lastTransitionChoice = choice;

        transitionToNextStateFromChosenMove();
      }

      if (playedMoves != null)
      {
        playedMoves.add(decisionState.chooserMoves[lastTransitionChoice]);
      }

      decisionState.nextChoiceIndex = lastTransitionChoice;
      do
      {
        decisionState.nextChoiceIndex = (decisionState.nextChoiceIndex + 1) %
                                        decisionState.numChoices;
        if (!decisionState.propProcessed[decisionState.nextChoiceIndex] ||
            decisionState.nextChoiceIndex == decisionState.baseChoiceIndex)
        {
          break;
        }
      }
      while (decisionState.nextChoiceIndex != choiceIndex);

      if (preEnumerate && numTerminals > 0)
      {
        greedyRolloutEffectiveness += (decisionState.numChoices - numTerminals) /
                                      decisionState.numChoices;
      }
    }
    else
    {
      for (int roleIndex = 0; roleIndex < numRoles; roleIndex++)
      {
        chosenJointMoveProps[roleIndex] = decisionState.nonChooserProps[roleIndex];
      }
      transitionToNextStateFromChosenMove();

      if (playedMoves != null)
      {
        playedMoves.add(choicelessMoveInfo);
      }
      if (isTerminal() || scoresAreLatched(lastInternalSetState))
      {
        results.considerResult(decisionState.choosingRole);
      }
    }

    return null;
    //        }
    //		finally
    //		{
    //			methodSection.exitScope();
    //		}
  }

  private boolean hasAvailableMoveForAllRoles()
  {
    for (int roleIndex = 0; roleIndex < numRoles; roleIndex++)
    {
      Collection<ForwardDeadReckonLegalMoveInfo> moves = propNet.getActiveLegalProps(instanceId).getContents(roleIndex);

      if (moves.isEmpty())
      {
        return false;
      }
    }

    return true;
  }

  private int chooseRandomJointMove(StateMachineFilter factor,
                                    MoveWeights moveWeights,
                                    List<ForwardDeadReckonLegalMoveInfo> playedMoves)
  {
		int result = 0;
    int index = 0;
    ForwardDeadReckonLegalMoveSet activeLegalMoves = propNet.getActiveLegalProps(instanceId);

    for (int roleIndex = 0; roleIndex < numRoles; roleIndex++)
    {
      ForwardDeadReckonLegalMoveSet moves = activeLegalMoves;

      //  In a factored game the terminal logic can sometimes span the factors in a way we don't
      //  cleanly cater for currently, so use lack of legal moves as a proxy for terminality.
      if ((factor != null) && (moves.getNumChoices(roleIndex) == 0))
      {
        return 0;
      }

      int numChoices = StateMachineFilterUtils.getFilteredSize(null, moves, roleIndex, factor, false);
      int rand;

      if (moveWeights == null)
      {
        rand = getRandom(numChoices);
      }
      else
      {
        double total = 0;

        Iterator<ForwardDeadReckonLegalMoveInfo> itr = moves.getContents(roleIndex).iterator();
        for (int iMove = 0; iMove < numChoices; iMove++)
        {
          // Get next move for this factor
          ForwardDeadReckonLegalMoveInfo info = StateMachineFilterUtils.nextFilteredMove(factor, itr);

          if (moveWeights.weightScore[info.masterIndex] == 0)
          {
            LOGGER.warn("Unexpected 0 move weight");
          }
          total += moveWeights.weightScore[info.masterIndex];
        }

        if (total == 0)
        {
          LOGGER.warn("Unexpected 0 move weight total");
        }
        rand = getRandom((int)total);
      }

      if (numChoices > result)
      {
        result = numChoices;
      }

      ForwardDeadReckonLegalMoveInfo chosen = null;

      Iterator<ForwardDeadReckonLegalMoveInfo> itr = moves.getContents(roleIndex).iterator();
      for (int iMove = 0; iMove < numChoices; iMove++)
      {
        // Get next move for this factor
        ForwardDeadReckonLegalMoveInfo info = StateMachineFilterUtils.nextFilteredMove(factor, itr);

        if (moveWeights == null)
        {
          rand--;
        }
        else
        {
          rand -= moveWeights.weightScore[info.masterIndex];
        }

        if (rand < 0)
        {
          chosen = info;
          break;
        }
      }

      assert(chosen != null);
      if (validationMachine != null)
      {
        chosenMoves[index] = chosen.move;
      }
      chosenJointMoveProps[index++] = chosen.inputProposition;
      if (playedMoves != null)
      {
        playedMoves.add(chosen);
      }
    }

    return result;
  }

  private class TerminalResultSet
  {
    int                                          mChoosingRoleIndex = -1;
    public int                                   mScoreForChoosingRole = -1;
    public ForwardDeadReckonInternalMachineState mState;

    public void considerResult(Role choosingRole)
    {
      if (mChoosingRoleIndex == -1)
      {
        // We haven't yet recorded the choosing role.  Do so now - if there is one.  There won't be one if either no
        // roles have a choice or more than one role has a choice in this state.
        if (choosingRole == null)
        {
          return;
        }

        for (mChoosingRoleIndex = 0; !roles[mChoosingRoleIndex].equals(choosingRole); mChoosingRoleIndex++) {/* Spin */}
      }

      // Would this result be chosen over the previous (if any)?
      int lScoreForChoosingRole = getGoal(roles[mChoosingRoleIndex]);
      if (mState == null || (lScoreForChoosingRole > mScoreForChoosingRole))
      {
        mScoreForChoosingRole = lScoreForChoosingRole;
        if (mState == null)
        {
          mState = new ForwardDeadReckonInternalMachineState(lastInternalSetState);
        }
        else
        {
          mState.copy(lastInternalSetState);
        }
      }
    }

    public void reset()
    {
      mChoosingRoleIndex = -1;
      mScoreForChoosingRole = -1;
    }
  }

  private int     rolloutDepth;
  private boolean enableGreedyRollouts = true;

  public void disableGreedyRollouts()
  {
    if (!enableGreedyRollouts)
    {
      return;
    }

    enableGreedyRollouts = false;

    if (instanceId == 0)
    {
      //	Fixup the cross-reference of the base propositions of the two networks
      for (Entry<GdlSentence, PolymorphicProposition> e : propNetX.getBasePropositions().entrySet())
      {
        ForwardDeadReckonProposition oProp =
                               (ForwardDeadReckonProposition)propNetOWithoutGoals.getBasePropositions().get(e.getKey());
        ForwardDeadReckonProposition xProp =
                               (ForwardDeadReckonProposition)propNetXWithoutGoals.getBasePropositions().get(e.getKey());
        ForwardDeadReckonPropositionCrossReferenceInfo info =
                 (ForwardDeadReckonPropositionCrossReferenceInfo)((ForwardDeadReckonProposition)e.getValue()).getInfo();

        info.xNetProp = xProp;
        info.oNetProp = oProp;

        xProp.setInfo(info);
        oProp.setInfo(info);
      }

      propNetXWithoutGoals.crystalize(masterInfoSet, masterLegalMoveSet, maxInstances);
      propNetOWithoutGoals.crystalize(masterInfoSet, masterLegalMoveSet, maxInstances);

      for(ForwardDeadReckonPropositionInfo info : masterInfoSet)
      {
        ForwardDeadReckonPropositionCrossReferenceInfo crInfo = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

        crInfo.xNetPropId = crInfo.xNetProp.id;
        crInfo.oNetPropId = crInfo.oNetProp.id;
      }

      propNetXWithoutGoals.reset(true);
      propNetOWithoutGoals.reset(true);

      //  Set move factor info and virtual-noop info
      setMoveInfoForPropnet(propNetXWithoutGoals);
      setMoveInfoForPropnet(propNetOWithoutGoals);
    }

    propNetO = propNetOWithoutGoals;
    propNetX = propNetXWithoutGoals;

    propNet = propNetX;
    lastInternalSetState = null;
    lastInternalSetStateX = null;
    lastInternalSetStateO = null;

    for(int i = 0; i < numRoles; i++)
    {
      previousMovePropsX[i] = null;
      previousMovePropsO[i] = null;
    }
  }

  private int totalNumMoves = 0;

  public MoveWeights createMoveWeights()
  {
    return new MoveWeights(totalNumMoves, getRoles().length);
  }

  public ForwardDeadReckonInternalMachineState getCurrentState()
  {
    return lastInternalSetState;
  }

  public int getDepthChargeResult(ForwardDeadReckonInternalMachineState state,
                                   Factor factor,
                                   Role role,
                                   final int[] stats,
                                   MoveWeights moveWeights,
                                   List<ForwardDeadReckonLegalMoveInfo> playedMoves,
                                   int cutoffDepth)
  {
    rolloutDepth = 0;
    boolean lUseGreedyRollouts = enableGreedyRollouts && (numRoles <= 2);

    for (int i = 0; i < numRoles; i++)
    {
      ForwardDeadReckonProposition xProp = previousMovePropsX[i];
      ForwardDeadReckonProposition oProp = previousMovePropsO[i];

      if ( xProp != null )
      {
        previousMovePropsX[i] = null;
        propNetX.setProposition(instanceId, xProp, false);
      }
      if ( oProp != null )
      {
        previousMovePropsO[i] = null;
        propNetO.setProposition(instanceId, oProp, false);
      }
    }

    if (validationMachine != null)
    {
      validationState = state.getMachineState();
    }
    setPropNetUsage(state);
    setBasePropositionsFromState(state, true);
    for (int i = 0; i < numRoles; i++)
    {
      previouslyChosenJointMovePropIdsX[i] = -1;
      previouslyChosenJointMovePropIdsO[i] = -1;
    }
    if (!lUseGreedyRollouts)
    {
      int totalChoices = 0;

      while (!isTerminal() && !scoresAreLatched(lastInternalSetState))
      {
        int numChoices = chooseRandomJointMove(factor, moveWeights, playedMoves);
        totalChoices += numChoices;
        transitionToNextStateFromChosenMove();
        rolloutDepth++;
        if ( rolloutDepth > cutoffDepth )
        {
          break;
        }
      }

      if (stats != null)
      {
        stats[0] = rolloutDepth;
        if ( rolloutDepth > 0 )
        {
          stats[1] = (totalChoices + rolloutDepth / 2) / rolloutDepth;
        }
        else
        {
          stats[1] = 0;
        }
      }
    }
    else
    {
      double branchingFactor = 0;

      if ( !isTerminal() )
      {
        mResultSet.reset();
        branchingFactor = recursiveGreedyRollout(mResultSet,
                                                 factor,
                                                 moveWeights,
                                                 playedMoves,
                                                 cutoffDepth);

        if (mResultSet.mChoosingRoleIndex != -1)
        {
          setPropNetUsage(mResultSet.mState);
          setBasePropositionsFromState(mResultSet.mState, true);
        }

        rolloutDepth = rolloutStackDepth;
      }

      if (stats != null)
      {
        stats[0] = rolloutStackDepth;
        stats[1] = (int)(branchingFactor + 0.5);
      }
    }
    for (int i = 0; i < numRoles; i++)
    {
      int xId = previouslyChosenJointMovePropIdsX[i];
      int oId = previouslyChosenJointMovePropIdsO[i];

      if ( xId != -1)
      {
        propNetX.animator.setComponentValue(instanceId, xId, false);
      }
      if ( oId != -1)
      {
        propNetO.animator.setComponentValue(instanceId, oId, false);
      }
    }

    return rolloutDepth;
  }

  public Set<Factor> getFactors()
  {
    return factors;
  }

  public Set<GdlSentence> getBasePropositions()
  {
    return fullPropNet.getBasePropositions().keySet();
  }

  public GdlSentence getXSentence()
  {
    return XSentence;
  }

  public GdlSentence getOSentence()
  {
    return OSentence;
  }

  private ForwardDeadReckonInternalMachineState lastGoalState = null;
  private ForwardDeadReckonInternalMachineState nextGoalState = null;

  public void setGoalsCalculator(GoalsCalculator calculator)
  {
    mGoalsCalculator = calculator;
  }

  public int getGoal(ForwardDeadReckonInternalMachineState state, Role role)
  {
    if ( mGoalsCalculator != null )
    {
      return mGoalsCalculator.getGoalValue(state == null ? lastInternalSetState : state, role);
    }

    InternalMachineStateIterator lIterator = mStateIterator;

    ForwardDeadReckonPropNet net;

    if (enableGreedyRollouts)
    {
      if (state != null)
      {
        setPropNetUsage(state);
        setBasePropositionsFromState(state, true);
      }

      net = propNet;
    }
    else
    {
      net = goalsNet;

      if (state == null)
      {
        state = lastInternalSetState;
      }

      if (lastGoalState == null)
      {
        for (PolymorphicProposition p : net.getBasePropositionsArray())
        {
          net.setProposition(instanceId, (ForwardDeadReckonProposition)p, false);
          //((ForwardDeadReckonProposition)p).setValue(false, instanceId);
        }

        lIterator.reset(state);
        while (lIterator.hasNext())
        {
          ForwardDeadReckonPropositionInfo s = lIterator.next();
          ForwardDeadReckonPropositionCrossReferenceInfo scr = (ForwardDeadReckonPropositionCrossReferenceInfo)s;
          if (scr.goalsNetProp != null)
          {
            net.setProposition(instanceId, scr.goalsNetProp, true);
            //scr.goalsNetProp.setValue(true, instanceId);
          }
        }

        if (lastGoalState == null)
        {
          lastGoalState = new ForwardDeadReckonInternalMachineState(state);
        }
        else
        {
          lastGoalState.copy(state);
        }
      }
      else if (!state.equals(lastGoalState))
      {
        if (nextGoalState == null)
        {
          nextGoalState = new ForwardDeadReckonInternalMachineState(state);
        }
        else
        {
          nextGoalState.copy(state);
        }

        lastGoalState.xor(state);

        lIterator.reset(lastGoalState);
        while (lIterator.hasNext())
        {
          ForwardDeadReckonPropositionInfo info = lIterator.next();
          ForwardDeadReckonProposition goalsNetProp = ((ForwardDeadReckonPropositionCrossReferenceInfo)info).goalsNetProp;
          if (goalsNetProp != null)
          {
            if (nextGoalState.contains(info))
            {
              net.setProposition(instanceId, goalsNetProp, true);
              //goalsNetProp.setValue(true, instanceId);
            }
            else
            {
              net.setProposition(instanceId, goalsNetProp, false);
              //goalsNetProp.setValue(false, instanceId);
            }
          }
        }

        lastGoalState.copy(nextGoalState);
      }
    }

    //  HACK - for factored games we might be determining terminality
    //  based on factor termination through lack of legal moves, but not
    //  actually have a complete game state that registers as terminal.
    //  In such cases the goal network may also rely on global terminality
    //  and we attempt to best-guess the actual goal in this case.  Specifically
    //  if the game is terminal in a factor but not globally terminal, and all
    //  roles report the same score, we ASSUME it should be a normalized draw
    //  with all scoring 50.
    if ( factors != null && !isTerminalUnfactored() && isTerminal() )
    {
      int observedResult = -1;
      boolean goalDifferentiated = false;
      boolean roleSeen = false;

      for(Role r : getRoles())
      {
        int value = extractRoleGoal(net, role);

        if ( observedResult == -1 )
        {
          observedResult = value;
        }
        else if ( observedResult != value )
        {
          if ( roleSeen )
          {
            break;
          }

          observedResult = value;
          goalDifferentiated = true;
        }

        if ( role.equals(r) )
        {
          roleSeen = true;
          if ( goalDifferentiated )
          {
            break;
          }
        }
      }

      if ( !goalDifferentiated )
      {
        observedResult = 50;
      }

      return observedResult;
    }

    return extractRoleGoal(net, role);
  }

  private int extractRoleGoal(ForwardDeadReckonPropNet net, Role role)
  {
    PolymorphicProposition[] goalProps = net.getGoalPropositions().get(role);
    int result = 0;

    for (PolymorphicProposition p : goalProps)
    {
      //ForwardDeadReckonComponent goalInput = (ForwardDeadReckonComponent)p.getSingleInput();
      //if (goalInput != null && goalInput.getValue(instanceId))
      //if (goalInput != null && net.getTransition(instanceId, goalInput))
      if (net.getComponentValue(instanceId, (ForwardDeadReckonComponent)p))
      {
        result = Integer.parseInt(p.getName().getBody().get(1).toString());
        break;
      }
    }

    return result;
  }
}
