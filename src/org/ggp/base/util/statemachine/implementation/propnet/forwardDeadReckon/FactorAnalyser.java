package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;

/**
 * @author steve
 * class responsible for analysing a game's propnet to determine its factors
 */
public class FactorAnalyser
{
  private ForwardDeadReckonPropNet propNet;
  private Set<Factor>              factors;
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

    public Set<PolymorphicProposition>  dependencies1 = new HashSet<>();
    public boolean                      dependenciesIncludeMove = false;
  }

  private Map<PolymorphicProposition, DependencyInfo> baseDependencies = new HashMap<>();

  public FactorAnalyser(ForwardDeadReckonPropNet propNet)
  {
    factors = new HashSet<>();

    this.propNet = propNet;
  }

  /**
   * @return  Number of factors identified
   */
  public int analyse()
  {
    //  Find the base dependencies of every base, transitting through input props to their
    //  associated legals
    for(PolymorphicProposition baseProp : propNet.getBasePropositions().values())
    {
      //System.out.println("Build dependencies for: " + baseProp);
      baseDependencies.put(baseProp, buildBaseDependencies(baseProp));
    }

    //  Now find subsets of the base props that are closed under the dependency relation
    //  Start by finding the closure for each base prop
    Map<PolymorphicProposition,Set<PolymorphicProposition>> dependencyClosures = new HashMap<>();

    for(PolymorphicProposition baseProp : propNet.getBasePropositions().values())
    {
      dependencyClosures.put(baseProp, findDependencyClosure(baseProp));
    }

    //  Now look for pure disjunctive inputs to goal and terminal
    Map<PolymorphicComponent, Set<PolymorphicProposition>> disjunctiveInputs = new HashMap<>();

    addDisjunctiveInputProps(propNet.getTerminalProposition(), disjunctiveInputs);
    //  TODO - same for goals

    //  Trim out from each disjunctive input set those propositions in the control set, which are only
    //  influenced by other base props independently of moves (usually step and control logic)
    Set<PolymorphicComponent> controlOnlyInputs = new HashSet<>();
    Set<PolymorphicProposition> controlSet = new HashSet<>();

    for(PolymorphicProposition baseProp : propNet.getBasePropositions().values())
    {
      DependencyInfo dInfo = baseDependencies.get(baseProp);

      if ( !dInfo.dependenciesIncludeMove )
      {
        controlSet.add(baseProp);
      }
    }

    for( Entry<PolymorphicComponent, Set<PolymorphicProposition>> e : disjunctiveInputs.entrySet())
    {
      e.getValue().removeAll(controlSet);
      if ( e.getValue().isEmpty())
      {
        controlOnlyInputs.add(e.getKey());
      }
      else
      {
        System.out.println("Disjunctive input of size: " + e.getValue().size());
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

    for( Entry<PolymorphicComponent, Set<PolymorphicProposition>> e : disjunctiveInputs.entrySet())
    {
      if ( e.getValue().size() > (propNet.getBasePropositions().size() - controlSet.size())/2 )
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
      Factor newFactor = new Factor();

      newFactor.addAll(disjunctiveInputs.values().iterator().next());
      factors.add(newFactor);

      for(Factor factor : factors)
      {
        boolean anyAdded;

        do
        {
          anyAdded = false;

          Set<PolymorphicComponent> inputsProcessed = new HashSet<>();

          for( Entry<PolymorphicComponent, Set<PolymorphicProposition>> e : disjunctiveInputs.entrySet())
          {
            if ( factor.containsAny(e.getValue()) )
            {
              factor.addAll(e.getValue());
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

    return Math.max(1, factors.size());
  }

  private DependencyInfo buildBaseDependencies(PolymorphicProposition p)
  {
    DependencyInfo result = new DependencyInfo();
    Set<PolymorphicComponent> visited = new HashSet<>();

    result.dependenciesIncludeMove = recursiveBuildBaseDependencies(p, p, result.dependencies1, visited);

    return result;
  }

  private void addDisjunctiveInputProps(PolymorphicComponent c, Map<PolymorphicComponent, Set<PolymorphicProposition>> disjunctiveInputs)
  {
    recursiveAddDisjunctiveInputProps(c.getSingleInput(), disjunctiveInputs);
  }

  private void recursiveAddDisjunctiveInputProps(PolymorphicComponent c, Map<PolymorphicComponent, Set<PolymorphicProposition>> disjunctiveInputs)
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
      Set<PolymorphicProposition> dependencies = new HashSet<>();
      Set<PolymorphicComponent> visited = new HashSet<>();

      recursiveBuildBaseDependencies(null, c, dependencies, visited);
      disjunctiveInputs.put(c, dependencies);
    }
  }

  private Set<PolymorphicProposition> findDependencyClosure(PolymorphicProposition p)
  {
    Set<PolymorphicProposition> result = new HashSet<>();

    recursiveBuildDependencyClosure(p, result);

    return result;
  }

  private void recursiveBuildDependencyClosure(PolymorphicProposition p, Set<PolymorphicProposition> closure)
  {
    if ( closure.contains(p))
    {
      return;
    }

    DependencyInfo dInfo = baseDependencies.get(p);
    if ( dInfo != null )
    {
      closure.add(p);
      for(PolymorphicProposition dependency : dInfo.dependencies1)
      {
        recursiveBuildDependencyClosure(dependency, closure);
      }
    }
  }

  //  Return true if at least one dependency involved transitioning across a does->legal relationship
  private boolean recursiveBuildBaseDependencies(PolymorphicProposition root, PolymorphicComponent c, Set<PolymorphicProposition> dependencies, Set<PolymorphicComponent> visited)
  {
    if ( visited.contains(c))
    {
      return false;
    }

    visited.add(c);

    //System.out.println("  ...trace back through: " + c);
    if ( c instanceof PolymorphicProposition )
    {
      if ( dependencies.contains(c))
      {
        return false;
      }

      PolymorphicProposition p = (PolymorphicProposition)c;
      GdlConstant name = p.getName().getName();

      if ( propNet.getBasePropositions().containsValue(p))
      {
        dependencies.add(p);
        root = p;
      }

      if (name.equals(INIT) )
      {
        return false;
      }

      if ( name.equals(DOES))
      {
        if ( root != null )
        {
          PolymorphicProposition legalProp = propNet.getLegalInputMap().get(c);

          if ( legalProp != null )
          {
            recursiveBuildBaseDependencies(root, legalProp, dependencies, visited);
            return true;
          }
        }

        return false;
      }
    }

    //  An AND that incldues the thing we're trying to find the dependencies of
    //  itself is not an interesting dependency since it cannot BECOME set through
    //  this path.  This helps weed out logic in the GDL that says a prop retains its
    //  current state when any move except some distinct clause is played
    if ( c instanceof PolymorphicAnd )
    {
      for(PolymorphicComponent input : c.getInputs())
      {
        if ( input == root )
        {
          return false;
        }
      }
    }

    boolean result = false;
    for(PolymorphicComponent input : c.getInputs())
    {
      result |= recursiveBuildBaseDependencies(root, input, dependencies, visited);
    }

    return result;
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
