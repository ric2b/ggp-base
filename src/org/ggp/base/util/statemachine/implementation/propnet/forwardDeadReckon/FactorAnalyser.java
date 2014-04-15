package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.statemachine.Move;

/**
 * @author steve
 * class responsible for analysing a game's propnet to determine its factors
 */
public class FactorAnalyser
{
  private ForwardDeadReckonPropnetStateMachine stateMachine;
  static final private GdlConstant    GOAL      = GdlPool.getConstant("goal");
  static final private GdlConstant    INIT      = GdlPool.getConstant("init");
  static final private GdlConstant    TERMINAL  = GdlPool.getConstant("terminal");
  static final private GdlConstant    LEGAL  = GdlPool.getConstant("legal");
  static final private GdlConstant    DOES  = GdlPool.getConstant("does");
  static final private GdlConstant    BASE  = GdlPool.getConstant("base");

  private class DependencyInfo
  {
    public DependencyInfo()
    {
    }

    public Set<PolymorphicProposition>  dependencies = new HashSet<>();
    public Set<Move>                    moves = new HashSet<>();
  }

  private Map<PolymorphicProposition, DependencyInfo> baseDependencies = new HashMap<>();

  public FactorAnalyser(ForwardDeadReckonPropnetStateMachine stateMachine)
  {
    this.stateMachine = stateMachine;
  }

  /**
   * @return  Number of factors identified
   */
  public Set<Factor> analyse()
  {
    Set<Factor>  factors = new HashSet<>();

    //  Find the base dependencies of every base, transitting through input props to their
    //  associated legals
    for(PolymorphicProposition baseProp : stateMachine.getFullPropNet().getBasePropositions().values())
    {
      //System.out.println("Build dependencies for: " + baseProp);
      baseDependencies.put(baseProp, buildBaseDependencies(baseProp));
    }

    //  Now look for pure disjunctive inputs to goal and terminal
    Map<PolymorphicComponent, DependencyInfo> disjunctiveInputs = new HashMap<>();

    addDisjunctiveInputProps(stateMachine.getFullPropNet().getTerminalProposition(), disjunctiveInputs);
    //  TODO - same for goals

    //  Trim out from each disjunctive input set those propositions in the control set, which are only
    //  influenced by other base props independently of moves (usually step and control logic)
    Set<PolymorphicComponent> controlOnlyInputs = new HashSet<>();
    Set<PolymorphicProposition> controlSet = new HashSet<>();

    for(PolymorphicProposition baseProp : stateMachine.getFullPropNet().getBasePropositions().values())
    {
      DependencyInfo dInfo = baseDependencies.get(baseProp);

      if ( dInfo.moves.isEmpty() )
      {
        controlSet.add(baseProp);
      }
    }

    for( Entry<PolymorphicComponent, DependencyInfo> e : disjunctiveInputs.entrySet())
    {
      e.getValue().dependencies.removeAll(controlSet);
      if ( e.getValue().dependencies.isEmpty())
      {
        controlOnlyInputs.add(e.getKey());
      }
    }

    //  Trim out those inputs which have no base dependencies
    for(PolymorphicComponent c : controlOnlyInputs)
    {
      disjunctiveInputs.remove(c);
    }

    //  TOTAL HACK - TEMPORARY (I hope)
    //  The way the GDL is written for several simultaneous games introduces an
    //  artificial pseudo-coupling for the termination condition that amounts to
    //  someone-has-no-moves-left 'stalemates'.  Examples are:
    //    All board positions occupied in C4
    //    One player has no pieces left in Breakthrough
    //  The GDL tends to express these as conditions across both games in factorized
    //  situations.  For instance simultaneous C4 expresses this as 'there are no blank
    //  cells left on either board' which amounts to a NOT of a large OR for every cell
    //  being red of black.
    //  In general these psuedo-couplings are quite hard to pick out in static analysis
    //  and there are a number of possible (somewhat) robust approaches we could try:
    //    1)  Disconnect them from the terminal clause and directly monitor them during
    //        simulation, but replace them in the terminal disjunction with the extra
    //        pseudo-condition 'the legal move set is empty'.  During metagaming simulate
    //        this modified game, and if the suspect disjunct we are monitoring directly
    //        only goes TRUE in states that the modified game finds to ANYWAY be terminal
    //        then they are not true couplings of the factors.
    //    2)  Rewrite them as a disjunctive approximation by making them ORs of two
    //        instances of their input logic obtained by pruning out all base props
    //        from one of the factors.  Simulate both versions of the game, and if
    //        they never disagree about termination then the coupling is not a true
    //        coupling and we can use the factored approximation
    //  HOWEVER, for now we take a much cheaper seem-to-get-away-with-it approach
    //  and consider the size of the dependency sets for the disjunctive propositions - if
    //  any of them involve more than half of the (non-control) base-props we assume they
    //  can be ignored for the purposes of determining factorization!
    Set<PolymorphicComponent> ignorableDisjuncts = new HashSet<>();

    for( Entry<PolymorphicComponent, DependencyInfo> e : disjunctiveInputs.entrySet())
    {
      if ( e.getValue().dependencies.size() > (stateMachine.getFullPropNet().getBasePropositions().size() - controlSet.size())/2 )
      {
        ignorableDisjuncts.add(e.getKey());
      }
    }

    for(PolymorphicComponent c : ignorableDisjuncts)
    {
      disjunctiveInputs.remove(c);
    }

    //  Now find sets of disjunctive inputs with non-intersecting base prop
    //  dependencies - these are the factors
    while(!disjunctiveInputs.isEmpty())
    {
      Factor newFactor = new Factor(stateMachine);

      newFactor.addAll(disjunctiveInputs.values().iterator().next().dependencies);
      factors.add(newFactor);

      for(Factor factor : factors)
      {
        boolean anyAdded;

        do
        {
          anyAdded = false;

          Set<PolymorphicComponent> inputsProcessed = new HashSet<>();

          for( Entry<PolymorphicComponent, DependencyInfo> e : disjunctiveInputs.entrySet())
          {
            if ( factor.containsAny(e.getValue().dependencies) )
            {
              factor.addAll(e.getValue().dependencies);
              factor.addAllMoves(e.getValue().moves);
              inputsProcessed.add(e.getKey());
              anyAdded = true;
            }
          }

          for(PolymorphicComponent p : inputsProcessed)
          {
            disjunctiveInputs.remove(p);
          }
        } while(anyAdded);
      }
    }

    //  If we only found 1 factor (or none, which is possible with the current hacky
    //  approach to handling global couplings) then we do not have a factorizable
    //  game, and we can leave the cross ref info recording null for the factor of every
    //  base prop, but if we have factorized then record the factor each base prop belongs to
    //  (if any - pure control props are not included in any factor)
    if ( factors.size() > 1 )
    {
      for(Factor factor : factors)
      {
        System.out.println("Found factor:");
        factor.dump();

        for(PolymorphicComponent c : factor.getComponents())
        {
          if (c instanceof PolymorphicProposition)
          {
            PolymorphicProposition p = (PolymorphicProposition)c;

            ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;
            ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fdrp.getInfo();

            info.factor = factor;
          }
        }
      }
    }

    return (factors.size() > 1 ? factors : null);
  }

  private DependencyInfo buildBaseDependencies(PolymorphicProposition p)
  {
    DependencyInfo result = new DependencyInfo();
    Set<PolymorphicComponent> visited = new HashSet<>();

    recursiveBuildBaseDependencies(p, p, result, visited);

    return result;
  }

  private void addDisjunctiveInputProps(PolymorphicComponent c, Map<PolymorphicComponent, DependencyInfo> disjunctiveInputs)
  {
    recursiveAddDisjunctiveInputProps(c.getSingleInput(), disjunctiveInputs);
  }

  private void recursiveAddDisjunctiveInputProps(PolymorphicComponent c, Map<PolymorphicComponent, DependencyInfo> disjunctiveInputs)
  {
    if ( c instanceof PolymorphicOr )
    {
      for(PolymorphicComponent input : c.getInputs())
      {
        recursiveAddDisjunctiveInputProps(input, disjunctiveInputs);
      }
    }
    else
    {
      //  For each disjunctive input find the base props it is dependent on
      DependencyInfo dependencies = new DependencyInfo();
      Set<PolymorphicComponent> visited = new HashSet<>();

      recursiveBuildBaseDependencies(null, c, dependencies, visited);
      disjunctiveInputs.put(c, dependencies);
    }
  }

  //  Return true if at least one dependency involved transitioning across a does->legal relationship
  private void recursiveBuildBaseDependencies(PolymorphicProposition root, PolymorphicComponent c, DependencyInfo dInfo, Set<PolymorphicComponent> visited)
  {
    if ( visited.contains(c))
    {
      return;
    }

    visited.add(c);

    //System.out.println("  ...trace back through: " + c);
    if ( c instanceof PolymorphicProposition )
    {
      if ( dInfo.dependencies.contains(c))
      {
        return;
      }

      PolymorphicProposition p = (PolymorphicProposition)c;
      GdlConstant name = p.getName().getName();

      if ( stateMachine.getFullPropNet().getBasePropositions().containsValue(p))
      {
        if ( baseDependencies.containsKey(p))
        {
          DependencyInfo ancestorKnownDependencies = baseDependencies.get(p);

          dInfo.dependencies.addAll(ancestorKnownDependencies.dependencies);
          dInfo.moves.addAll(ancestorKnownDependencies.moves);
          return;
        }

        dInfo.dependencies.add(p);
        root = p;
      }

      if (name.equals(INIT) )
      {
        return;
      }

      if ( name.equals(DOES))
      {
        if ( root != null )
        {
          PolymorphicProposition legalProp = stateMachine.getFullPropNet().getLegalInputMap().get(c);

          if ( legalProp != null )
          {
            recursiveBuildBaseDependencies(root, legalProp, dInfo, visited);
            dInfo.moves.add(new Move(legalProp.getName().getBody().get(1)));
            return;
          }
        }
      }
    }

    //  The following is a somewhat heuristic approach to getting around 'fake' dependencies
    //  introduced by distinct clauses in the GDL.  Specifically games often have rules of this
    //  type (taken from KnighThrough):
    //    (<= (next (cell ?x ?y ?state))
    //        (true (cell ?x ?y ?state))
    //        (does ?player (move ?x1 ?y1 ?x2 ?y2))
    //        (distinctcell ?x ?y ?x1 ?y1)
    //        (distinctcell ?x ?y ?x2 ?y2))
    //  This is saying that a cell retains its state unless someone moves into or out of it
    //  The problem is that it encodes this logic by testing that what is played is one of the
    //  set of moves that is not a move into or out of the cell in question - i.e. - a vast OR
    //  consisting of most moves in the game, and in particular, including all the moves on the
    //  'other board' relative to the tested cell.  Naively this coupling of a move on the other
    //  board to the next state of a cell on this board looks like a dependency that prevents
    //  factorization, but we must prevent this being seen as a 'real' coupling.  This is a result
    //  of the (not encoded into the propnet) requirement (for a well formed game) that exactly
    //  one move must be played each turn, so this huge OR is equivalent to the NOT of a much smaller
    //  OR for the complimentary set of moves.  We convert ORs involving more than half of the moves in
    //  the game with that transformation and calculate the dependencies with respect to the result.
    if ( c instanceof PolymorphicOr )
    {
      Collection<PolymorphicProposition> inputProps = stateMachine.getFullPropNet().getInputPropositions().values();
      if ( c.getInputs().size() > inputProps.size()/2 )
      {
        Set<PolymorphicComponent> inputPropsOred = new HashSet<>();

        for(PolymorphicComponent input : c.getInputs())
        {
          if ( inputProps.contains(input))
          {
            inputPropsOred.add(input);
          }
        }

        if ( inputPropsOred.size() > inputProps.size()/2 )
        {
          for(PolymorphicComponent input : c.getInputs())
          {
            if ( !inputPropsOred.contains(input))
            {
              recursiveBuildBaseDependencies(root, input, dInfo, visited);
            }
          }
          for(PolymorphicComponent input : inputProps)
          {
            if ( !inputPropsOred.contains(input))
            {
              recursiveBuildBaseDependencies(root, input, dInfo, visited);
            }
          }

          return;
        }
      }
    }

    for(PolymorphicComponent input : c.getInputs())
    {
      recursiveBuildBaseDependencies(root, input, dInfo, visited);
    }
  }

  private void recursiveBuildFactorForwards(Factor factor, PolymorphicComponent c)
  {
    if ( factor.containsComponent(c))
    {
      return;
    }

    //  Terminal and goal and init props do not constitute factor links
    if ( c instanceof PolymorphicProposition )
    {
      PolymorphicProposition p = (PolymorphicProposition)c;
      GdlConstant name = p.getName().getName();

      if (name.equals(INIT) || name.equals(LEGAL) || name.equals(GOAL) || name.equals(TERMINAL) )
      {
        return;
      }

      recursiveBuildFactorBackwards(factor, c);
    }

    factor.addComponent(c);

    for(PolymorphicComponent output : c.getOutputs())
    {
      if ( output instanceof ForwardDeadReckonProposition )
      {
        ForwardDeadReckonProposition p = (ForwardDeadReckonProposition)output;

        ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)p.getInfo();
        //  Only base props have info structures associated with them currently.  Any other props
        //  we simply treat like generic components for the purposes of flowing factor colouring
        //  through them
        if ( info != null )
        {
          info.factor = factor;
        }
      }

      recursiveBuildFactorForwards(factor, output);
    }
  }

  private void recursiveBuildFactorBackwards(Factor factor, PolymorphicComponent c)
  {
    if ( factor.containsComponent(c))
    {
      return;
    }

    factor.addComponent(c);

    //  Terminal and goal and init props do not constitute factor links
    if ( c instanceof PolymorphicProposition )
    {
      PolymorphicProposition p = (PolymorphicProposition)c;
      GdlConstant name = p.getName().getName();

      if (name.equals(INIT) || name.equals(GOAL) || name.equals(TERMINAL) )
      {
        return;
      }
    }

    for(PolymorphicComponent input : c.getInputs())
    {
      if ( input instanceof ForwardDeadReckonProposition )
      {
        ForwardDeadReckonProposition p = (ForwardDeadReckonProposition)input;

        ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)p.getInfo();

        //  Only base props have info structures associated with them currently.  Any other props
        //  we simply treat like generic components for the purposes of flowing factor colouring
        //  through them
        if ( info != null )
        {
          info.factor = factor;
        }
        recursiveBuildFactorForwards(factor, input);
      }
      else
      {
        recursiveBuildFactorBackwards(factor, input);
      }
    }
  }
}
