
package org.ggp.base.util.statemachine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;


/**
 * Provides the base class for all state machine implementations.
 */
public abstract class StateMachine
{
  // ============================================
  //          Stubs for implementations
  // ============================================
  //  The following methods are required for a valid
  // state machine implementation.
  /**
   * Initializes the StateMachine to describe the given game rules.
   * <p>
   * This method should only be called once, and it should be called before any
   * other methods on the StateMachine.
   */
  public abstract void initialize(List<Gdl> description);

  /**
   * Returns the goal value for the given role in the given state. Goal values
   * are always between 0 and 100.
   * 
   * @throws GoalDefinitionException
   *           if there is no goal value or more than one goal value for the
   *           given role in the given state. If this occurs when this is
   *           called on a terminal state, this indicates an error in either
   *           the game description or the StateMachine implementation.
   */
  public abstract int getGoal(MachineState state, Role role)
      throws GoalDefinitionException;

  /**
   * Returns true if and only if the given state is a terminal state (i.e. the
   * game is over).
   */
  public abstract boolean isTerminal(MachineState state);

  /**
   * Returns a list of the roles in the game, in the same order as they were
   * defined in the game description.
   * <p>
   * The result will be the same as calling {@link Role#computeRoles(List)} on
   * the game rules used to initialize this state machine.
   */
  public abstract List<Role> getRoles();

  /**
   * Returns the initial state of the game.
   */
  public abstract MachineState getInitialState();

  private Random randomGen = new Random();

  public void setRandomSeed(long seed)
  {
    randomGen.setSeed(seed);
  }

  protected int getRandom(int n)
  {
    return randomGen.nextInt(n);
  }

  // TODO: There are philosophical reasons for this to return Set<Move> rather than List<Move>.
  public abstract List<Move> getLegalMoves(MachineState state, Role role)
      throws MoveDefinitionException;

  public abstract MachineState getNextState(MachineState state,
                                            List<Move> moves)
      throws TransitionDefinitionException;

  // The following methods are included in the abstract StateMachine base so
  // implementations which use alternative Role/Move/State representations
  // can look up/compute what some Gdl corresponds to in their representation.
  // They are implemented for convenience, using the default ways of generating
  // these objects, but they can be overridden to support machine-specific objects.
  public MachineState getMachineStateFromSentenceList(Set<GdlSentence> sentenceList)
  {
    return new MachineState(sentenceList);
  }

  public Role getRoleFromConstant(GdlConstant constant)
  {
    return new Role(constant);
  }

  public Move getMoveFromTerm(GdlTerm term)
  {
    return new Move(term);
  }

  // ============================================
  //          Stubs for advanced methods
  // ============================================
  //
  //   The following methods have functioning stubs,
  // which can be overridden with full-fledged versions
  // as needed by state machines. Clients should assume
  // the contracts for these methods hold, regardless
  // of the state machine implementation they pick.

  // Override this to perform some extra work (like trimming a cache) once per move.
  // CONTRACT: Should be called once per move.
  public void doPerMoveWork()
  {
  }

  // Override this to provide memory-saving destructive-next-state functionality.
  // CONTRACT: After calling this method, "state" should not be accessed.
  public MachineState getNextStateDestructively(MachineState state,
                                                List<Move> moves)
      throws TransitionDefinitionException
  {
    return getNextState(state, moves);
  }

  // Override this to allow the state machine to be conditioned on a particular current state.
  // This means that the state machine will only handle portions of the game tree at and below
  // the given state; it no longer needs to properly handle earlier portions of the game tree.
  // This constraint can be used to optimize certain state machine implementations.
  // CONTRACT: After calling this method, the state machine never deals with a state that
  //           is not "theState" or one of its descendants in the game tree.
  public void updateRoot(MachineState theState)
  {
    ;
  }

  // ============================================
  //   Implementations of convenience methods
  // ============================================

  public String getName()
  {
    return this.getClass().getSimpleName();
  }

  public List<List<Move>> getLegalJointMoves(MachineState state)
      throws MoveDefinitionException
  {
    List<List<Move>> legals = new ArrayList<List<Move>>();
    for (Role role : getRoles())
    {
      legals.add(getLegalMoves(state, role));
    }

    List<List<Move>> crossProduct = new ArrayList<List<Move>>();
    crossProductLegalMoves(legals, crossProduct, new LinkedList<Move>());

    return crossProduct;
  }

  public List<List<Move>> getLegalJointMoves(MachineState state,
                                             Role role,
                                             Move move)
      throws MoveDefinitionException
  {
    List<List<Move>> legals = new ArrayList<List<Move>>();
    for (Role r : getRoles())
    {
      if (r.equals(role))
      {
        List<Move> m = new ArrayList<Move>();
        m.add(move);
        legals.add(m);
      }
      else
      {
        legals.add(getLegalMoves(state, r));
      }
    }

    List<List<Move>> crossProduct = new ArrayList<List<Move>>();
    crossProductLegalMoves(legals, crossProduct, new LinkedList<Move>());

    return crossProduct;
  }

  public List<MachineState> getNextStates(MachineState state)
      throws MoveDefinitionException, TransitionDefinitionException
  {
    List<MachineState> nextStates = new ArrayList<MachineState>();
    for (List<Move> move : getLegalJointMoves(state))
    {
      nextStates.add(getNextState(state, move));
    }

    return nextStates;
  }

  public Map<Move, List<MachineState>> getNextStates(MachineState state,
                                                     Role role)
      throws MoveDefinitionException, TransitionDefinitionException
  {
    Map<Move, List<MachineState>> nextStates = new HashMap<Move, List<MachineState>>();
    Map<Role, Integer> roleIndices = getRoleIndices();
    for (List<Move> moves : getLegalJointMoves(state))
    {
      Move move = moves.get(roleIndices.get(role));
      if (!nextStates.containsKey(move))
      {
        nextStates.put(move, new ArrayList<MachineState>());
      }
      nextStates.get(move).add(getNextState(state, moves));
    }

    return nextStates;
  }

  protected void crossProductLegalMoves(List<List<Move>> legals,
                                        List<List<Move>> crossProduct,
                                        LinkedList<Move> partial)
  {
    if (partial.size() == legals.size())
    {
      crossProduct.add(new ArrayList<Move>(partial));
    }
    else
    {
      for (Move move : legals.get(partial.size()))
      {
        partial.addLast(move);
        crossProductLegalMoves(legals, crossProduct, partial);
        partial.removeLast();
      }
    }
  }

  private Map<Role, Integer> roleIndices = null;

  public Map<Role, Integer> getRoleIndices()
  {
    if (roleIndices == null)
    {
      roleIndices = new HashMap<Role, Integer>();
      List<Role> roles = getRoles();
      for (int i = 0; i < roles.size(); i++)
      {
        roleIndices.put(roles.get(i), i);
      }
    }

    return roleIndices;
  }

  public List<Integer> getGoals(MachineState state)
      throws GoalDefinitionException
  {
    List<Integer> theGoals = new ArrayList<Integer>();
    for (Role r : getRoles())
    {
      theGoals.add(getGoal(state, r));
    }
    return theGoals;
  }

  public List<Move> getRandomJointMove(MachineState state)
      throws MoveDefinitionException
  {
    List<Move> random = new ArrayList<Move>();
    for (Role role : getRoles())
    {
      random.add(getRandomMove(state, role));
    }

    return random;
  }

  public List<Move> getRandomJointMove(MachineState state, Role role, Move move)
      throws MoveDefinitionException
  {
    List<Move> random = new ArrayList<Move>();
    for (Role r : getRoles())
    {
      if (r.equals(role))
      {
        random.add(move);
      }
      else
      {
        random.add(getRandomMove(state, r));
      }
    }

    return random;
  }

  public Move getRandomMove(MachineState state, Role role)
      throws MoveDefinitionException
  {
    List<Move> legals = getLegalMoves(state, role);
    int randIndex = getRandom(legals.size());
    return legals.get(randIndex);
  }

  public MachineState getRandomNextState(MachineState state)
      throws MoveDefinitionException, TransitionDefinitionException
  {
    List<Move> random = getRandomJointMove(state);
    return getNextState(state, random);
  }

  public MachineState getRandomNextState(MachineState state,
                                         Role role,
                                         Move move)
      throws MoveDefinitionException, TransitionDefinitionException
  {
    List<Move> random = getRandomJointMove(state, role, move);
    return getNextState(state, random);
  }

  public MachineState performDepthCharge(MachineState state,
                                         final int[] theDepth)
      throws TransitionDefinitionException, MoveDefinitionException
  {
    int nDepth = 0;
    while (!isTerminal(state))
    {
      nDepth++;
      state = getNextStateDestructively(state, getRandomJointMove(state));
    }
    if (theDepth != null)
      theDepth[0] = nDepth;
    return state;
  }

  public void getAverageDiscountedScoresFromRepeatedDepthCharges(final MachineState state,
                                                                 final double[] avgScores,
                                                                 final double[] avgDepth,
                                                                 final double discountFactor,
                                                                 final int repetitions)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    avgDepth[0] = 0;
    for (int j = 0; j < avgScores.length; j++)
    {
      avgScores[j] = 0;
    }
    final int[] depth = new int[1];
    for (int i = 0; i < repetitions; i++)
    {
      MachineState stateForCharge = state.clone();
      stateForCharge = performDepthCharge(stateForCharge, depth);
      avgDepth[0] += depth[0];
      final double accumulatedDiscountFactor = Math.pow(discountFactor,
                                                        depth[0]);
      for (int j = 0; j < avgScores.length; j++)
      {
        avgScores[j] += getGoal(stateForCharge, getRoles().get(j)) *
                        accumulatedDiscountFactor;
      }
    }
    avgDepth[0] /= repetitions;
    for (int j = 0; j < avgScores.length; j++)
    {
      avgScores[j] /= repetitions;
    }
  }
}