package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.statemachine.Move;

/**
 * @author steve
 *
 * Class representing a factor within a game's propnet.  A factor is a partition within a partitioning of the base
 * propositions into disjoint sets between which there are no causative logical connections or coupling via
 * terminal/goal conditions.
 */
public class Factor
{
  private static final ForwardDeadReckonLegalMoveInfo PSEUDO_NO_OP = new ForwardDeadReckonLegalMoveInfo(true);

  private Set<PolymorphicComponent> components = new HashSet<>();
  private Set<Move> moves = new HashSet<>();
  private ForwardDeadReckonInternalMachineState stateMask = null;
  private ForwardDeadReckonInternalMachineState factorSpecificStateMask = null;
  private ForwardDeadReckonInternalMachineState inverseStateMask = null;
  private ForwardDeadReckonInternalMachineState inverseFactorSpecificStateMask = null;
  private ForwardDeadReckonPropnetStateMachine stateMachine;
  private boolean alwaysIncludePseudoNoop = false;

  public Factor(ForwardDeadReckonPropnetStateMachine stateMachine)
  {
    this.stateMachine = stateMachine;
  }

  public boolean containsComponent(PolymorphicComponent c)
  {
    return components.contains(c);
  }

  public void addComponent(PolymorphicComponent c)
  {
    components.add(c);
  }

  public Set<PolymorphicComponent> getComponents()
  {
    return components;
  }

  public Set<Move> getMoves()
  {
    return moves;
  }

  public boolean containsAny(Collection<? extends PolymorphicComponent> toTest)
  {
    for(PolymorphicComponent c : toTest)
    {
      if ( components.contains(c))
      {
        return true;
      }
    }

    return false;
  }

  public void addAll(Collection<? extends PolymorphicComponent> toAdd)
  {
    components.addAll(toAdd);
  }

  public void addAllMoves(Collection<Move> toAdd)
  {
    moves.addAll(toAdd);
  }

  public void dump()
  {
    System.out.println("Factor base props:");
    for(PolymorphicComponent c : components)
    {
      if ( c instanceof PolymorphicProposition )
      {
        PolymorphicProposition p = (PolymorphicProposition)c;

        System.out.println("  " + p.getName());
      }
    }

    System.out.println("Factor moves:");
    for(Move move : moves)
    {
      System.out.println("  " + move);
    }
  }

  private void setUpStateMasks()
  {
    factorSpecificStateMask = new ForwardDeadReckonInternalMachineState(stateMachine.getInfoSet());
    for(PolymorphicProposition p : stateMachine.getFullPropNet().getBasePropositions().values())
    {
      ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;
      ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fdrp.getInfo();

      if ( info.factor == this )
      {
        factorSpecificStateMask.add(info);
      }
    }
    stateMask = new ForwardDeadReckonInternalMachineState(stateMachine.getInfoSet());
    for(PolymorphicProposition p : stateMachine.getFullPropNet().getBasePropositions().values())
    {
      ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;
      ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fdrp.getInfo();

      if ( info.factor == null )
      {
        stateMask.add(info);
      }
    }
    stateMask.merge(factorSpecificStateMask);

    inverseStateMask = new ForwardDeadReckonInternalMachineState(stateMask);
    inverseStateMask.invert();
    inverseFactorSpecificStateMask = new ForwardDeadReckonInternalMachineState(factorSpecificStateMask);
    inverseFactorSpecificStateMask.invert();
  }

  public ForwardDeadReckonInternalMachineState getStateMask(boolean stateSpecificOnly)
  {
    if ( factorSpecificStateMask == null )
    {
      setUpStateMasks();
    }

    return (stateSpecificOnly ? factorSpecificStateMask : stateMask);
  }

  public ForwardDeadReckonInternalMachineState getInverseStateMask(boolean stateSpecificOnly)
  {
    if ( factorSpecificStateMask == null )
    {
      setUpStateMasks();
    }

    return (stateSpecificOnly ? inverseFactorSpecificStateMask : inverseStateMask);
  }

  public boolean getAlwaysIncludePseudoNoop()
  {
    return alwaysIncludePseudoNoop;
  }

  public void setAlwaysIncludePseudoNoop(boolean value)
  {
    alwaysIncludePseudoNoop = value;;
  }

  /**
   * Filter a move against a factor.
   *
   * @param xiMove                   - the move.
   * @param xiFactor                 - the factor.
   * @param includeForcedPseudoNoops - whether to return a pseduo-no-op if the move isn't valid for the factor.
   *
   * @return either the move, a pseudo-no-op or null depending on whether the move is part of the factor and whether
   * pseudo-no-ops were requested.
   */
  public static ForwardDeadReckonLegalMoveInfo filterMove(ForwardDeadReckonLegalMoveInfo xiMove,
                                                          Factor xiFactor,
                                                          boolean includeForcedPseudoNoops)
  {
    if (xiFactor == null)
    {
      // Non-factored game.  All moves pass the filter.
      return xiMove;
    }

    if (xiMove.factor == null || xiMove.factor == xiFactor)
    {
      // The specified move is valid in all factors or in this factor.
      return xiMove;
    }

    if (includeForcedPseudoNoops /* && xiFactor.getAlwaysIncludePseudoNoop() */)
    {
      // The specified move is not valid and we've been asked to substitute a pseudo-no-op.
      return PSEUDO_NO_OP;
    }

    // The specified move is not part of this factor and we haven't been asked to substitute a pseudo-no-op.
    return null;
  }

  /**
   * @return the number of moves in the specified collection that are valid for the specified factor.
   *
   * @param xiMoves  - the moves.
   * @param xiFactor - the factor.
   */
  public static int getFilteredSize(Collection<ForwardDeadReckonLegalMoveInfo> xiMoves, Factor xiFactor)
  {
    if (xiFactor == null)
    {
      // Non-factored game.  All moves in the underlying collection are valid.
      return xiMoves.size();
    }

    int lCount = 0;
    for (ForwardDeadReckonLegalMoveInfo lMove : xiMoves)
    {
      if (filterMove(lMove, xiFactor, (lCount == 0)) != null)
      {
        lCount++;
      }
    }
    return lCount;
  }
}
