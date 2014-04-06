
package org.ggp.base.util.statemachine.implementation.propnet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlRelation;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.architecture.components.Proposition;
import org.ggp.base.util.propnet.factory.OptimizingPropNetFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponentFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;
import org.ggp.base.util.propnet.polymorphic.learning.LearningComponent;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.query.ProverQueryBuilder;
import org.ggp.base.util.stats.Stats;


@SuppressWarnings("unused")
public class TestPropnetStateMachine extends StateMachine
{
  /** The underlying proposition network */
  private PolymorphicPropNet                propNet                    = null;
  private final PolymorphicComponentFactory defaultComponentFactory;
  private PropNet                           basicPropNet;
  private PolymorphicComponent[]            basePropositionTransitions = null;
  /** The player roles */
  private List<Role>                        roles;
  private MachineState                      lastSetState               = null;
  private final boolean                     useSampleOfKnownLegals     = false;

  public TestPropnetStateMachine(PolymorphicComponentFactory componentFactory)
  {
    defaultComponentFactory = componentFactory;
  }

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

    public void addResetCount(int resetCount)
    {
      numStateSettings++;
      totalResets += resetCount;
    }

    public void addGetCount(int getCount)
    {
      numStateFetches++;
      totalGets += getCount;
    }
  }

  private TestPropnetStateMachineStats stats;

  public Stats getStats()
  {
    return stats;
  }

  /**
   * Initializes the PropNetStateMachine. You should compute the topological
   * ordering here. Additionally you may compute the initial state here, at
   * your discretion.
   */
  @Override
  public void initialize(List<Gdl> description)
  {
    setRandomSeed(1);

    try
    {
      basicPropNet = OptimizingPropNetFactory.create(description, false);
      basicPropNet.renderToFile("c:\\temp\\propnet.dot");

      OptimizingPropNetFactory.removeAnonymousPropositions(basicPropNet);
      OptimizingPropNetFactory.removeUnreachableBasesAndInputs(basicPropNet);
      OptimizingPropNetFactory.removeRedundantConstantsAndGates(basicPropNet);

      basicPropNet.renderToFile("c:\\temp\\propnetReduced.dot");

      roles = basicPropNet.getRoles();
      stats = new TestPropnetStateMachineStats(basicPropNet
          .getBasePropositions().size(), basicPropNet.getInputPropositions()
          .size(), basicPropNet.getLegalPropositions().get(getRoles().get(0))
          .size());
      //	Assess network statistics
      int numInputs = 0;
      int numMultiInputs = 0;
      int numMultiInputComponents = 0;

      for (Component c : basicPropNet.getComponents())
      {
        int n = c.getInputs().size();

        numInputs += n;

        if (n > 1)
        {
          numMultiInputComponents++;
          numMultiInputs += n;
        }
      }

      int numComponents = basicPropNet.getComponents().size();
      System.out.println("Num components: " + numComponents +
                         " with an average of " + numInputs / numComponents +
                         " inputs.");
      System.out
          .println("Num multi-input components: " +
                   numMultiInputComponents +
                   " with an average of " +
                   (numMultiInputComponents == 0 ? "N/A" : numMultiInputs /
                                                           numMultiInputComponents) +
                   " inputs.");

      recreate(defaultComponentFactory);
      //recreate(new RuntimeOptimizedComponentFactory());
    }
    catch (InterruptedException e)
    {
      // TODO: handle exception
    }
  }

  public void Optimize()
  {
    for (PolymorphicComponent c : propNet.getComponents())
    {
      ((LearningComponent)c).Optimize();
    }
  }

  public void recreate(PolymorphicComponentFactory factory)
  {
    if (basicPropNet != null)
    {
      propNet = new PolymorphicPropNet(basicPropNet, factory);

      basicPropNet = null;
    }
    else
    {
      propNet.renderToFile("c:\\temp\\pre-recreate.dot");

      propNet = new PolymorphicPropNet(propNet, factory);
    }

    for (PolymorphicComponent c : propNet.getComponents())
    {
      ((BidirectionalPropagationComponent)c).reset(true);
    }

    propNet.renderToFile("c:\\temp\\recreate.dot");

    basePropositionTransitions = new PolymorphicComponent[propNet
        .getBasePropositionsArray().length];
    int index = 0;
    for (PolymorphicComponent p : propNet.getBasePropositionsArray())
    {
      basePropositionTransitions[index++] = p.getSingleInput();
    }
  }

  private void setBasePropositionsFromState(MachineState state)
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.setBasePropositions");
    try
    {
      if (lastSetState != null)
      {
        for (GdlSentence s : state.getContents())
        {
          if (!lastSetState.getContents().contains(s))
          {
            propNet.getBasePropositions().get(s).setValue(true);
          }
        }
        for (GdlSentence s : lastSetState.getContents())
        {
          if (!state.getContents().contains(s))
          {
            propNet.getBasePropositions().get(s).setValue(false);
          }
        }
      }
      else
      {
        for (PolymorphicProposition p : propNet.getBasePropositionsArray())
        {
          if (state.getContents().contains(p.getName()))
          {
            p.setValue(true);
          }
          else
          {
            p.setValue(false);
          }
        }
      }

      lastSetState = state;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  /**
   * Computes if the state is terminal. Should return the value of the terminal
   * proposition for the state.
   */
  @Override
  public boolean isTerminal(MachineState state)
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.isTerminal");
    try
    {
      setBasePropositionsFromState(state);

      PolymorphicProposition terminalProp = propNet.getTerminalProposition();
      return terminalProp.getSingleInput().getValue();
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  /**
   * Computes the goal for a role in the current state. Should return the value
   * of the goal proposition that is true for that role. If there is not
   * exactly one goal proposition true for that role, then you should throw a
   * GoalDefinitionException because the goal is ill-defined.
   */
  @Override
  public int getGoal(MachineState state, Role role)
      throws GoalDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getGoal");
    try
    {
      setBasePropositionsFromState(state);

      PolymorphicProposition[] goalProps = propNet.getGoalPropositions()
          .get(role);
      int result = 0;

      for (PolymorphicProposition p : goalProps)
      {
        if (p.getSingleInput().getValue())
        {
          result = Integer.parseInt(p.getName().getBody().get(1).toString());
        }
      }

      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  /**
   * Returns the initial state. The initial state can be computed by only
   * setting the truth value of the INIT proposition to true, and then
   * computing the resulting state.
   */
  @Override
  public MachineState getInitialState()
  {
    for (PolymorphicComponent c : propNet.getComponents())
    {
      ((BidirectionalPropagationComponent)c).reset(true);
    }
    propNet.getInitProposition().setValue(true);
    MachineState result = getStateFromBase();
    propNet.getInitProposition().setValue(false);
    for (PolymorphicComponent c : propNet.getComponents())
    {
      ((BidirectionalPropagationComponent)c).reset(false);
    }

    lastSetState = null;
    return result;
  }

  /**
   * Computes the legal moves for role in state.
   */
  @Override
  public List<Move> getLegalMoves(MachineState state, Role role)
      throws MoveDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getLegalMoves");
    try
    {
      setBasePropositionsFromState(state);

      PolymorphicProposition[] legalProps = propNet.getLegalPropositions()
          .get(role);
      List<Move> result = new LinkedList<Move>();

      for (PolymorphicProposition p : legalProps)
      {
        if (p.getSingleInput().getValue())
        {
          result.add(new Move(p.getName().getBody().get(1)));
        }
      }

      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  /**
   * Checks whether a specified move is legal for role in state.
   */
  public boolean isLegalMove(MachineState state, Role role, Move move)
      throws MoveDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.isLegalMove");
    try
    {
      setBasePropositionsFromState(state);

      Map<GdlSentence, PolymorphicProposition> inputProps = propNet
          .getInputPropositions();

      GdlSentence moveSentence = ProverQueryBuilder.toDoes(role, move);
      PolymorphicProposition moveInputProposition = inputProps
          .get(moveSentence);
      PolymorphicProposition legalProp = propNet.getLegalInputMap()
          .get(moveInputProposition);
      if (legalProp != null)
      {
        return legalProp.getSingleInput().getValue();
      }

      PolymorphicProposition[] legalProps = propNet.getLegalPropositions()
          .get(role);

      for (PolymorphicProposition p : legalProps)
      {
        if (p.getName().getBody().get(1).equals(move.getContents()))
        {
          //return p.getSingleInput().getValue();
        }
      }

      throw new MoveDefinitionException(state, role);
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  /**
   * Computes the next state given state and the list of moves.
   */
  @Override
  public MachineState getNextState(MachineState state, List<Move> moves)
      throws TransitionDefinitionException
  {
    //System.out.println("Get next state after " + moves + " from: " + state);
    //RuntimeOptimizedComponent.getCount = 0;
    //RuntimeOptimizedComponent.dirtyCount = 0;
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getNextState");
    try
    {
      setBasePropositionsFromState(state);

      Map<GdlSentence, PolymorphicProposition> inputProps = propNet
          .getInputPropositions();

      for (GdlSentence moveSentence : toDoes(moves))
      {
        PolymorphicProposition moveInputProposition = inputProps
            .get(moveSentence);
        moveInputProposition.setValue(true);
      }

      MachineState result = getStateFromBase();

      for (GdlSentence moveSentence : toDoes(moves))
      {
        PolymorphicProposition moveInputProposition = inputProps
            .get(moveSentence);
        moveInputProposition.setValue(false);
      }

      return result;
    }
    catch (Exception ex)
    {
      //System.out.println("Machine get count: " + 	RuntimeOptimizedComponent.getCount);
      //System.out.println("Machine dirty count: " + 	RuntimeOptimizedComponent.dirtyCount);

      return state;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  /* Already implemented for you */
  @Override
  public List<Role> getRoles()
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
  private List<GdlSentence> toDoes(List<Move> moves)
  {
    List<GdlSentence> doeses = new ArrayList<GdlSentence>(moves.size());
    Map<Role, Integer> roleIndices = getRoleIndices();

    for (int i = 0; i < roles.size(); i++)
    {
      int index = roleIndices.get(roles.get(i));
      doeses.add(ProverQueryBuilder.toDoes(roles.get(i), moves.get(index)));
    }
    return doeses;
  }

  /**
   * Takes in a Legal Proposition and returns the appropriate corresponding
   * Move
   *
   * @param p
   * @return a PropNetMove
   */
  public static Move getMoveFromProposition(Proposition p)
  {
    return new Move(p.getName().get(1));
  }

  /**
   * Helper method for parsing the value of a goal proposition
   *
   * @param goalProposition
   * @return the integer value of the goal proposition
   */
  private int getGoalValue(Proposition goalProposition)
  {
    GdlRelation relation = (GdlRelation)goalProposition.getName();
    GdlConstant constant = (GdlConstant)relation.get(1);
    return Integer.parseInt(constant.toString());
  }

  /**
   * A Naive implementation that computes a PropNetMachineState from the true
   * BasePropositions. This is correct but slower than more advanced
   * implementations You need not use this method!
   *
   * @return PropNetMachineState
   */
  public MachineState getStateFromBase()
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getStateFromBase");
    try
    {
      //RuntimeOptimizedComponent.getCount = 0;

      Set<GdlSentence> contents = new HashSet<GdlSentence>();
      int numBaseProps = basePropositionTransitions.length;

      for (int i = 0; i < numBaseProps; i++)
      {
        PolymorphicComponent t = basePropositionTransitions[i];
        if (t.getValue())
        {
          contents.add(propNet.getBasePropositionsArray()[i].getName());
        }
      }
      //stats.addGetCount(RuntimeOptimizedComponent.getCount);

      return new MachineState(contents);
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  private Map<Role, List<Move>> recentLegalMoveSetsList = new HashMap<Role, List<Move>>();

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
        Move result = previouslyAvailableMoves
            .get(getRandom(previouslyAvailableMoves.size()));

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
        candidates = new LinkedList<Move>();

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
}
