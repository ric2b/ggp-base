
package org.ggp.base.util.statemachine.implementation.propnet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlRelation;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.architecture.components.Proposition;
import org.ggp.base.util.propnet.factory.OptimizingPropNetFactory;
import org.ggp.base.util.propnet.factory.PropNetFactory;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;
import org.ggp.base.util.statemachine.implementation.prover.query.ProverQueryBuilder;
import org.ggp.base.util.statemachine.verifier.StateMachineVerifier;

public class PropNetStateMachine extends StateMachine
{
  // !! ARR Debug variable for mark sequencing
  private static int                    sUniqueRenderID;

  /**
   * The underlying PropNet.
   */
  private PropNet                       mNet;

  /**
   * Propositions in the order that their values should be computed. By
   * following this order, it is guaranteed that all the input values for
   * proposition p are computed before the value of p.
   */
  private List<Proposition>             mOrderedProps;

  /**
   * Convenience variable (for mNet.getBasePropositions()).
   */
  private Map<GdlSentence, Proposition> mBasePropositions;

  /**
   * Convenience variable (for mNet.getRoles()).
   */
  private List<Role>                    mRoles;

  /**
   * Initialise the PropNetStateMachine.
   */
  @Override
  public void initialize(List<Gdl> xiGDL)
  {
    //-------------------------------------------------------------------------
    // Create the underlying PropNet.
    //-------------------------------------------------------------------------

    //--------------------------------------------------------------------------
    // !!  ARR OPNF doesn't work for now because computePropOrder relies on
    //         the graph being bipartite, but I don't think OFPN makes bipartite
    //         graphs.
    //--------------------------------------------------------------------------
    final boolean lUseOPNF = true;
    if (lUseOPNF)
    {
      try
      {
        mNet = OptimizingPropNetFactory.create(xiGDL);
      }
      catch (final InterruptedException lEx)
      {
        throw new RuntimeException(lEx);
      }
    }
    else
    {
      mNet = PropNetFactory.create(xiGDL);
    }

    debugRender(mNet);

    //-------------------------------------------------------------------------
    // Set up the convenience variables.
    //-------------------------------------------------------------------------
    mBasePropositions = mNet.getBasePropositions();
    mRoles = mNet.getRoles();

    //-------------------------------------------------------------------------
    // Compute the order in which proposition values should be calculated.
    //-------------------------------------------------------------------------
    if (lUseOPNF)
    {
      mOrderedProps = computePropOrderingForOPN();
    }
    else
    {
      mOrderedProps = computePropOrdering();
    }

    //--------------------------------------------------------------------------
    // !! ARR Verify the state machine we've produced against the supplied one.
    //--------------------------------------------------------------------------
    // debugVerify(xiGDL);
  }

  private void debugVerify(List<Gdl> xiGDL)
  {
    System.out.println("Verifying...");
    final StateMachine lReference = new ProverStateMachine();
    lReference.initialize(xiGDL);
    StateMachineVerifier.checkMachineConsistency(lReference, this, 5000);
    System.out.println("Verification complete");
  }

  /**
   * @return a list of propositions in the order that their values should be
   *         computed. By following this order, it is guaranteed that all the
   *         input values for proposition p are computed before the value of p.
   */
  private List<Proposition> computePropOrdering()
  {
    final List<Proposition> lOrdered = new LinkedList<Proposition>();

    //--------------------------------------------------------------------------
    // Take a copy of the list of all propositions.  We modify this list in
    // this function and don't want to destroy the underlying list.  A shallow
    // copy suffices.  (We don't change the underlying propositions.)
    //--------------------------------------------------------------------------
    final List<Proposition> lUnordered = new LinkedList<Proposition>(mNet.getPropositions());

    //--------------------------------------------------------------------------
    // Compute the ordered list.
    //
    // To do this, take an item from the list of those that are not yet
    // ordered.  See if all its dependencies have been met (i.e. the inputs to
    // its input component are already all in the set of ordered
    // propositions).  If all the dependencies are met, move it to the tail of
    // the ordered list.  If not, just put it to the back of the unordered
    // list.  We'll consider it again later.
    //
    // Repeat until the unordered list is empty.
    //
    // This algorithm relies on the PropNet graph being bipartite with all
    // edges connecting a proposition to a non-proposition.
    //--------------------------------------------------------------------------
    while (!lUnordered.isEmpty())
    {
      //------------------------------------------------------------------------
      // Remove the item at the head of the unordered list.
      //------------------------------------------------------------------------
      final Proposition lProp = lUnordered.remove(0);

      //------------------------------------------------------------------------
      // We don't want fundamental propositions (base/input/init props) to
      // appear in the final output.
      //------------------------------------------------------------------------
      if (isFundmentalProposition(lProp))
      {
        continue;
      }

      //------------------------------------------------------------------------
      // Get the (immediate) input propositions that affect this one.
      //
      // For unoptimized graphs, all propositions have a single input - from a
      // component that isn't a proposition.  Get the inputs to that component.
      // (They're all propositions because this is a bipartite graph.)
      //------------------------------------------------------------------------
      final Collection<Component> lPropsToCheck = lProp.getSingleInput()
          .getInputs();

      //------------------------------------------------------------------------
      // All propositions have a single input - from a component that isn't a
      // proposition.  Get the inputs to that component.  (They're all
      // propositions because this is a bipartite graph.)
      //
      // We hope to be able to add the proposition to the end of the ordered
      // list.  If we can't (because one of the inputs isn't yet on the ordered
      // list) we'll update this within the loop below.
      //------------------------------------------------------------------------
      List<Proposition> lListToAdd = lOrdered;
      for (final Component lComponent : lPropsToCheck)
      {
        if (!((lOrdered.contains(lComponent)) || (isFundmentalProposition((Proposition)lComponent))))
        {
          lListToAdd = lUnordered;
          break;
        }
      }
      lListToAdd.add(lProp);
    }

    return lOrdered;
  }

  /**
   * @return a list of propositions in the order that their values should be
   *         computed. By following this order, it is guaranteed that all the
   *         input values for proposition p are computed before the value of p.
   */
  private List<Proposition> computePropOrderingForOPN()
  {
    final List<Proposition> lOrdered = new LinkedList<Proposition>();

    //--------------------------------------------------------------------------
    // Take a copy of the list of all propositions.  We modify this list in
    // this function and don't want to destroy the underlying list.  A shallow
    // copy suffices.  (We don't change the underlying propositions.)
    //--------------------------------------------------------------------------
    final List<Proposition> lUnordered = new LinkedList<Proposition>(mNet.getPropositions());

    //--------------------------------------------------------------------------
    // Compute the ordered list.
    //
    // To do this, take an item from the list of those that are not yet
    // ordered.  See if all its dependencies have been met (i.e. the inputs to
    // its input component are already all in the set of ordered
    // propositions).  If all the dependencies are met, move it to the tail of
    // the ordered list.  If not, just put it to the back of the unordered
    // list.  We'll consider it again later.
    //
    // Repeat until the unordered list is empty.
    //--------------------------------------------------------------------------
    while (!lUnordered.isEmpty())
    {
      //------------------------------------------------------------------------
      // Remove the item at the head of the unordered list.
      //------------------------------------------------------------------------
      final Proposition lProp = lUnordered.remove(0);

      //------------------------------------------------------------------------
      // We don't want fundamental propositions (base/input/init props) to
      // appear in the final output.
      //------------------------------------------------------------------------
      if (isFundmentalProposition(lProp))
      {
        continue;
      }

      //------------------------------------------------------------------------
      // Get the (immediate) input propositions that affect this one.
      //------------------------------------------------------------------------
      final Set<Proposition> lPropsToCheck = getImmediateInputs(lProp);

      //------------------------------------------------------------------------
      // We hope to be able to add the proposition to the end of the ordered
      // list.  If we can't (because one of the inputs isn't yet on the ordered
      // list) we'll update this within the loop below.
      //------------------------------------------------------------------------
      List<Proposition> lListToAdd = lOrdered;
      for (final Proposition lInput : lPropsToCheck)
      {
        if (!((lOrdered.contains(lInput)) || (isFundmentalProposition(lInput))))
        {
          lListToAdd = lUnordered;
          break;
        }
      }
      lListToAdd.add(lProp);
    }

    return lOrdered;
  }

  private static Set<Proposition> getImmediateInputs(Proposition xiProposition)
  {
    final Set<Proposition> lInputs = new HashSet<Proposition>();
    final List<Component> lToConsider = new LinkedList<Component>();
    lToConsider.addAll(xiProposition.getInputs());
    while (!lToConsider.isEmpty())
    {
      final Component lComponent = lToConsider.remove(0);

      if (lComponent instanceof Proposition)
      {
        lInputs.add((Proposition)lComponent);
      }
      else
      {
        lToConsider.addAll(lComponent.getInputs());
      }
    }
    return lInputs;
  }

  /**
   * @return whether the specified proposition is a base or input proposition.
   * @param xiProp
   *          - the proposition.
   */
  private boolean isFundmentalProposition(Proposition xiProp)
  {
    return ((mBasePropositions.containsValue(xiProp)) ||
            (mNet.getInputPropositions().containsValue(xiProp)) || (mNet
        .getInitProposition().equals(xiProp)));
  }

  /**
   * Mark the network with the specified state.
   * 
   * @param xiState
   *          - the state.
   */
  private void markNetwork(MachineState xiState, List<Move> xiMoves)
  {
    //-------------------------------------------------------------------------
    // Remove existing markings.
    //-------------------------------------------------------------------------
    for (final Proposition lProp : mBasePropositions.values())
    {
      lProp.setValue(false);
    }
    for (final Proposition lProp : mNet.getInputPropositions().values())
    {
      lProp.setValue(false);
    }
    mNet.getInitProposition().setValue(false);

    //-------------------------------------------------------------------------
    // Set base markings (if any).
    //-------------------------------------------------------------------------
    if (xiState == null)
    {
      //-----------------------------------------------------------------------
      // No machine state.  We're trying to get the initial state, so set the
      // special "init" proposition.
      //-----------------------------------------------------------------------
      mNet.getInitProposition().setValue(true);
    }
    else
    {
      //-----------------------------------------------------------------------
      // Machine state provided.  Mark the network accordingly.
      //-----------------------------------------------------------------------
      for (final GdlSentence lSentence : xiState.getContents())
      {
        mBasePropositions.get(lSentence).setValue(true);
      }
    }

    //-------------------------------------------------------------------------
    // Set input markings (if any).
    //-------------------------------------------------------------------------
    if (xiMoves != null)
    {
      final Map<GdlSentence, Proposition> lInputProps = mNet
          .getInputPropositions();
      for (final GdlTerm lTerm : toDoes(xiMoves))
      {
        lInputProps.get(lTerm.toSentence()).setValue(true);
      }
    }

    //-------------------------------------------------------------------------
    // Propagate the markings throughout the network.
    //-------------------------------------------------------------------------
    propagateMarkings();
  }

  /**
   * Propagate a network which has its initial markings.
   */
  private void propagateMarkings()
  {
    // debugRender(mNet);
    for (final Proposition lProp : mOrderedProps)
    {
      lProp.setValue(lProp.getSingleInput().getValue());
    }
    // debugRender(mNet);
  }

  /**
   * @return the initial state.
   */
  @Override
  public MachineState getInitialState()
  {
    markNetwork(null, null);

    //-------------------------------------------------------------------------
    // For each proposition that is now true, get the GdlSentence
    // representation.  Use these to construct the initial machine state.
    //-------------------------------------------------------------------------
    final Set<GdlSentence> lSentences = new HashSet<GdlSentence>();
    for (final GdlSentence lSentence : mBasePropositions.keySet())
    {
      final Proposition prop = mBasePropositions.get(lSentence);
      if (prop.getSingleInput().getValue())
      {
        lSentences.add(lSentence);
      }
    }

    return new MachineState(lSentences);
  }

  @Override
  public List<Role> getRoles()
  {
    return mRoles;
  }

  /**
   * @return the legal moves for the specified role in the specified state.
   * @param xiState
   *          - the state.
   * @param xiRole
   *          - the role.
   */
  @Override
  public List<Move> getLegalMoves(MachineState xiState, Role xiRole)
  {
    markNetwork(xiState, null);

    final List<Move> lLegalMoves = new ArrayList<Move>();
    final Set<Proposition> lPotentialMoves = mNet.getLegalPropositions()
        .get(xiRole);
    for (final Proposition lProp : lPotentialMoves)
    {
      if (lProp.getValue())
      {
        lLegalMoves.add(getMoveFromProposition(lProp));
      }
    }

    return lLegalMoves;
  }

  /**
   * @return the next state when the specified moves are executed in the
   *         specified state.
   * @param xiState
   *          - the state.
   * @param xiMoves
   *          - the moves.
   */
  @Override
  public MachineState getNextState(MachineState xiState, List<Move> xiMoves)
      throws TransitionDefinitionException
  {
    markNetwork(xiState, xiMoves);
    advance();

    final Set<GdlSentence> sentences = new HashSet<GdlSentence>();
    for (final GdlSentence lSentence : mBasePropositions.keySet())
    {
      final Proposition prop = mBasePropositions.get(lSentence);
      if (prop.getValue())
      {
        sentences.add(lSentence);
      }
    }

    return new MachineState(sentences);
  }

  private void advance()
  {
    for (final Proposition lProp : mBasePropositions.values())
    {
      lProp.setValue(lProp.getSingleInput().getValue());
    }
    propagateMarkings();
  }

  /**
   * @return whether the specified state is terminal.
   * @param xiState
   *          - the state.
   */
  @Override
  public boolean isTerminal(MachineState xiState)
  {
    markNetwork(xiState, null);
    return mNet.getTerminalProposition().getValue();
  }

  /**
   * @return the goal value for the specified role in the specified state.
   * @param xiState
   *          - the state.
   * @param xiRole
   *          - the role.
   * @throws GoalDefinitionException
   *           if the state isn't terminal or if the game is poorly defined.
   */
  @Override
  public int getGoal(MachineState xiState, Role xiRole)
      throws GoalDefinitionException
  {
    markNetwork(xiState, null);

    Integer lValue = null;

    final Set<Proposition> lGoalProps = mNet.getGoalPropositions().get(xiRole);
    for (final Proposition lProp : lGoalProps)
    {
      if (lProp.getValue())
      {
        if (lValue != null)
        {
          throw new GoalDefinitionException(xiState, xiRole);
        }
        lValue = getGoalValue(lProp);
      }
    }

    if (lValue == null)
    {
      throw new GoalDefinitionException(xiState, xiRole);
    }

    return lValue;
  }

  /* Helper methods */
  /**
   * The Input propositions are indexed by (does ?player ?action) This
   * translates a List of Moves (backed by a sentence that is simply ?action)
   * to GdlTerms that can be used to get Propositions from inputPropositions
   * and accordingly set their values etc. This is a naive implementation when
   * coupled with setting input values, feel free to change this for a more
   * efficient implementation.
   * 
   * @param moves
   * @return
   */
  private List<GdlTerm> toDoes(List<Move> moves)
  {
    final List<GdlTerm> doeses = new ArrayList<GdlTerm>(moves.size());
    final Map<Role, Integer> roleIndices = getRoleIndices();

    for (int i = 0; i < mRoles.size(); i++)
    {
      final int index = roleIndices.get(mRoles.get(i));
      doeses.add(ProverQueryBuilder.toDoes(mRoles.get(i), moves.get(index))
          .toTerm());
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
  private static Move getMoveFromProposition(Proposition p)
  {
    return new Move(p.getName().get(1));
  }

  /**
   * Helper method for parsing the value of a goal proposition
   * 
   * @param goalProposition
   * @return the integer value of the goal proposition
   */
  private static int getGoalValue(Proposition goalProposition)
  {
    final GdlRelation relation = (GdlRelation)goalProposition.getName();
    final GdlConstant constant = (GdlConstant)relation.get(1);
    return Integer.parseInt(constant.toString());
  }

  /**
   * Debugging function to render the specified network to file.
   */
  private static synchronized void debugRender(PropNet xiNetwork)
  {
    xiNetwork.renderToFile("c:\\temp\\propnet\\" + sUniqueRenderID + ".dot");
    sUniqueRenderID++;
  }
}