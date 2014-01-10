package org.ggp.base.util.propnet.polymorphic.factory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;

import org.ggp.base.util.concurrency.ConcurrencyUtils;
import org.ggp.base.util.gdl.GdlUtils;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlDistinct;
import org.ggp.base.util.gdl.grammar.GdlLiteral;
import org.ggp.base.util.gdl.grammar.GdlNot;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlProposition;
import org.ggp.base.util.gdl.grammar.GdlRelation;
import org.ggp.base.util.gdl.grammar.GdlRule;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.gdl.grammar.GdlVariable;
import org.ggp.base.util.gdl.model.SentenceDomainModel;
import org.ggp.base.util.gdl.model.SentenceDomainModelFactory;
import org.ggp.base.util.gdl.model.SentenceDomainModelOptimizer;
import org.ggp.base.util.gdl.model.SentenceForm;
import org.ggp.base.util.gdl.model.SentenceForms;
import org.ggp.base.util.gdl.model.SentenceModelUtils;
import org.ggp.base.util.gdl.model.assignments.AssignmentIterator;
import org.ggp.base.util.gdl.model.assignments.Assignments;
import org.ggp.base.util.gdl.model.assignments.AssignmentsFactory;
import org.ggp.base.util.gdl.model.assignments.FunctionInfo;
import org.ggp.base.util.gdl.model.assignments.FunctionInfoImpl;
import org.ggp.base.util.gdl.transforms.CommonTransforms;
import org.ggp.base.util.gdl.transforms.CondensationIsolator;
import org.ggp.base.util.gdl.transforms.ConstantChecker;
import org.ggp.base.util.gdl.transforms.ConstantCheckerFactory;
import org.ggp.base.util.gdl.transforms.DeORer;
import org.ggp.base.util.gdl.transforms.GdlCleaner;
import org.ggp.base.util.gdl.transforms.Relationizer;
import org.ggp.base.util.gdl.transforms.VariableConstrainer;
import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.architecture.components.And;
import org.ggp.base.util.propnet.architecture.components.Constant;
import org.ggp.base.util.propnet.architecture.components.Not;
import org.ggp.base.util.propnet.architecture.components.Or;
import org.ggp.base.util.propnet.architecture.components.Proposition;
import org.ggp.base.util.propnet.architecture.components.Transition;
import org.ggp.base.util.propnet.factory.OptimizingPropNetFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponentFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.statemachine.Role;













import com.google.common.collect.Multimap;


/*
 * A propnet factory meant to optimize the propnet before it's even built,
 * mostly through transforming the GDL. (The transformations identify certain
 * classes of rules that have poor performance and replace them with equivalent
 * rules that have better performance, with performance measured by the size of
 * the propnet.)
 * 
 * Known issues:
 * - Does not work on games with many advanced forms of recursion. These include:
 *   - Anything that breaks the SentenceModel
 *   - Multiple sentence forms which reference one another in rules
 *   - Not 100% confirmed to work on games where recursive rules have multiple
 *     recursive conjuncts
 * - Currently runs some of the transformations multiple times. A Description
 *   object containing information about the description and its properties would
 *   alleviate this.
 * - Its current solution to the "unaffected piece rule" problem is somewhat
 *   clumsy and ungeneralized, relying on the combined behaviors of CrudeSplitter
 *   and CondensationIsolator.
 *   - The mutex finder in particular is very ungeneralized. It should be replaced
 *     with a more general mutex finder.
 *   - Actually, the referenced solution is not even enabled at the moment. It may
 *     not be working even with the proper options set.
 * - Depending on the settings and the situation, the behavior of the
 *   CondensationIsolator can be either too aggressive or not aggressive enough.
 *   Both result in excessively large games. A more sophisticated version of the
 *   CondensationIsolator could solve these problems. A stopgap alternative is to
 *   try both settings and use the smaller propnet (or the first to be created,
 *   if multithreading).
 * 
 */
public class OptimizingPolymorphicPropNetFactory {
	static final private GdlConstant LEGAL = GdlPool.getConstant("legal");
	static final private GdlConstant NEXT = GdlPool.getConstant("next");
	static final private GdlConstant TRUE = GdlPool.getConstant("true");
	static final private GdlConstant DOES = GdlPool.getConstant("does");
	static final private GdlConstant GOAL = GdlPool.getConstant("goal");
	static final private GdlConstant INIT = GdlPool.getConstant("init");
	//TODO: This currently doesn't actually give a different constant from INIT
	static final private GdlConstant INIT_CAPS = GdlPool.getConstant("INIT");
	static final private GdlConstant TERMINAL = GdlPool.getConstant("terminal");
    static final private GdlConstant BASE = GdlPool.getConstant("base");
    static final private GdlConstant INPUT = GdlPool.getConstant("input");
	static final private GdlProposition TEMP = GdlPool.getProposition(GdlPool.getConstant("TEMP"));

	/**
	 * Creates a PropNet for the game with the given description.
	 *
	 * @throws InterruptedException if the thread is interrupted during
	 * PropNet creation.
	 */
	public static PolymorphicPropNet create(List<Gdl> description, PolymorphicComponentFactory componentFactory) throws InterruptedException {
		return create(description, componentFactory, false);
	}

	public static PolymorphicPropNet create(List<Gdl> description, PolymorphicComponentFactory componentFactory, boolean verbose) throws InterruptedException {
		System.out.println("Building propnet...");

		long startTime = System.currentTimeMillis();

		description = GdlCleaner.run(description);
		description = DeORer.run(description);
		description = VariableConstrainer.replaceFunctionValuedVariables(description);
		description = Relationizer.run(description);

		description = CondensationIsolator.run(description);		    
		
		
		if(verbose)
			for(Gdl gdl : description)
				System.out.println(gdl);

		//We want to start with a rule graph and follow the rule graph.
		//Start by finding general information about the game
		SentenceDomainModel model = SentenceDomainModelFactory.createWithCartesianDomains(description);
		//Restrict domains to values that could actually come up in rules.
		//See chinesecheckers4's "count" relation for an example of why this
		//could be useful.
		model = SentenceDomainModelOptimizer.restrictDomainsToUsefulValues(model);

		if(verbose)
			System.out.println("Setting constants...");

		ConstantChecker constantChecker = ConstantCheckerFactory.createWithForwardChaining(model);
		if(verbose)
			System.out.println("Done setting constants");

		Set<String> sentenceFormNames = SentenceForms.getNames(model.getSentenceForms());
		boolean usingBase = sentenceFormNames.contains("base");
		boolean usingInput = sentenceFormNames.contains("input");


		//For now, we're going to build this to work on those with a
		//particular restriction on the dependency graph:
		//Recursive loops may only contain one sentence form.
		//This describes most games, but not all legal games.
		Multimap<SentenceForm, SentenceForm> dependencyGraph = model.getDependencyGraph();
		if(verbose) {
			System.out.print("Computing topological ordering... ");
			System.out.flush();
		}
		ConcurrencyUtils.checkForInterruption();
		List<SentenceForm> topologicalOrdering = getTopologicalOrdering(model.getSentenceForms(), dependencyGraph, usingBase, usingInput);
		if(verbose)
			System.out.println("done");

		List<Role> roles = Role.computeRoles(description);
		Map<GdlSentence, PolymorphicComponent> components = new HashMap<GdlSentence, PolymorphicComponent>();
		Map<GdlSentence, PolymorphicComponent> negations = new HashMap<GdlSentence, PolymorphicComponent>();
		PolymorphicConstant trueComponent = componentFactory.createConstant(-1, true);
		PolymorphicConstant falseComponent = componentFactory.createConstant(-1, false);
		Map<SentenceForm, FunctionInfo> functionInfoMap = new HashMap<SentenceForm, FunctionInfo>();
		Map<SentenceForm, Collection<GdlSentence>> completedSentenceFormValues = new HashMap<SentenceForm, Collection<GdlSentence>>();
		for(SentenceForm form : topologicalOrdering) {
			ConcurrencyUtils.checkForInterruption();

			if(verbose) {
				System.out.print("Adding sentence form " + form);
				System.out.flush();
			}
			if(constantChecker.isConstantForm(form)) {
				if(verbose)
					System.out.println(" (constant)");
				//Only add it if it's important
				if(form.getName().equals(LEGAL)
						|| form.getName().equals(GOAL)
						|| form.getName().equals(INIT)) {
					//Add it
					for (GdlSentence trueSentence : constantChecker.getTrueSentences(form)) {
						PolymorphicProposition trueProp = componentFactory.createProposition(-1, trueSentence);
						trueProp.addInput(trueComponent);
						trueComponent.addOutput(trueProp);
						components.put(trueSentence, trueComponent);
					}
				}

				if(verbose)
					System.out.println("Checking whether " + form + " is a functional constant...");
				addConstantsToFunctionInfo(form, constantChecker, functionInfoMap);
				addFormToCompletedValues(form, completedSentenceFormValues, constantChecker);

				continue;
			}
			if(verbose)
				System.out.println();
			//TODO: Adjust "recursive forms" appropriately
			//Add a temporary sentence form thingy? ...
			Map<GdlSentence, PolymorphicComponent> temporaryComponents = new HashMap<GdlSentence, PolymorphicComponent>();
			Map<GdlSentence, PolymorphicComponent> temporaryNegations = new HashMap<GdlSentence, PolymorphicComponent>();
			addSentenceForm(form, model, components, negations, trueComponent, falseComponent, usingBase, usingInput, Collections.singleton(form), temporaryComponents, temporaryNegations, functionInfoMap, constantChecker, completedSentenceFormValues, componentFactory);
			//TODO: Pass these over groups of multiple sentence forms
			if(verbose && !temporaryComponents.isEmpty())
				System.out.println("Processing temporary components...");
			processTemporaryComponents(temporaryComponents, temporaryNegations, components, negations, trueComponent, falseComponent);
			addFormToCompletedValues(form, completedSentenceFormValues, components);
			//if(verbose)
				//TODO: Add this, but with the correct total number of components (not just Propositions)
				//System.out.println("  "+completedSentenceFormValues.get(form).size() + " components added");
		}
		//Connect "next" to "true"
		if(verbose)
			System.out.println("Adding transitions...");
		addTransitions(components, componentFactory);
		//Set up "init" proposition
		if(verbose)
			System.out.println("Setting up 'init' proposition...");
		setUpInit(components, trueComponent, falseComponent, componentFactory);
		//Now we can safely...
		if(verbose)
			System.out.println("Num components before useless removed: " + components.size());
		
		removeUselessBasePropositions(components, negations, trueComponent, falseComponent);
		if(verbose)
			System.out.println("Num components after useless removed: " + components.size());
		if(verbose)
			System.out.println("Creating component set...");
		Set<PolymorphicComponent> componentSet = new HashSet<PolymorphicComponent>(components.values());
		//Try saving some memory here...
		components = null;
		negations = null;
		completeComponentSet(componentSet);
		ConcurrencyUtils.checkForInterruption();
		if(verbose)
			System.out.println("Initializing propnet object...");
		//Make it look the same as the PropNetFactory results, until we decide
		//how we want it to look
		normalizePropositions(componentSet);
		PolymorphicPropNet propnet = componentFactory.createPropNet(roles, componentSet);
		//System.out.println(propnet);
		return propnet;
	}


	private static void removeUselessBasePropositions(
			Map<GdlSentence, PolymorphicComponent> components, Map<GdlSentence, PolymorphicComponent> negations, PolymorphicConstant trueComponent,
			PolymorphicConstant falseComponent) throws InterruptedException {
		boolean changedSomething = false;
		for(Entry<GdlSentence, PolymorphicComponent> entry : components.entrySet()) {
			if(entry.getKey().getName() == TRUE) {
				PolymorphicComponent comp = entry.getValue();
				if(comp.getInputs().size() == 0) {
					comp.addInput(falseComponent);
					falseComponent.addOutput(comp);
					changedSomething = true;
				}
			}
		}
		if(!changedSomething)
			return;

		optimizeAwayTrueAndFalse(components, negations, trueComponent, falseComponent);
	}

	/**
	 * Changes the propositions contained in the propnet so that they correspond
	 * to the outputs of the PropNetFactory. This is for consistency and for
	 * backwards compatibility with respect to state machines designed for the
	 * old propnet factory. Feel free to remove this for your player.
	 * 
	 * @param componentSet
	 */
	private static void normalizePropositions(Set<PolymorphicComponent> componentSet) {
		for(PolymorphicComponent component : componentSet) {
			if(component instanceof PolymorphicProposition) {
				PolymorphicProposition p = (PolymorphicProposition) component;
				GdlSentence sentence = p.getName();
				if(sentence instanceof GdlRelation) {
					GdlRelation relation = (GdlRelation) sentence;
					if(relation.getName().equals(NEXT)) {
						p.setName(GdlPool.getProposition(GdlPool.getConstant("anon")));
					}
				}
			}
		}
	}

	private static void addFormToCompletedValues(
			SentenceForm form,
			Map<SentenceForm, Collection<GdlSentence>> completedSentenceFormValues,
			ConstantChecker constantChecker) {
		List<GdlSentence> sentences = new ArrayList<GdlSentence>();
		sentences.addAll(constantChecker.getTrueSentences(form));

		completedSentenceFormValues.put(form, sentences);
	}


	private static void addFormToCompletedValues(
			SentenceForm form,
			Map<SentenceForm, Collection<GdlSentence>> completedSentenceFormValues,
			Map<GdlSentence, PolymorphicComponent> components) throws InterruptedException {
		//Kind of inefficient. Could do better by collecting these as we go,
		//then adding them back into the CSFV map once the sentence forms are complete.
		//completedSentenceFormValues.put(form, new ArrayList<GdlSentence>());
		List<GdlSentence> sentences = new ArrayList<GdlSentence>();
		for(GdlSentence sentence : components.keySet()) {
			ConcurrencyUtils.checkForInterruption();
			if(form.matches(sentence)) {
				//The sentence has a node associated with it
				sentences.add(sentence);
			}
		}
		completedSentenceFormValues.put(form, sentences);
	}


	private static void addConstantsToFunctionInfo(SentenceForm form,
			ConstantChecker constantChecker, Map<SentenceForm, FunctionInfo> functionInfoMap) throws InterruptedException {
		functionInfoMap.put(form, FunctionInfoImpl.create(form, constantChecker));
	}


	private static void processTemporaryComponents(
			Map<GdlSentence, PolymorphicComponent> temporaryComponents,
			Map<GdlSentence, PolymorphicComponent> temporaryNegations,
			Map<GdlSentence, PolymorphicComponent> components,
			Map<GdlSentence, PolymorphicComponent> negations, PolymorphicComponent trueComponent,
			PolymorphicComponent falseComponent) throws InterruptedException {
		//For each component in temporary components, we want to "put it back"
		//into the main components section.
		//We also want to do optimization here...
		//We don't want to end up with anything following from true/false.

		//Everything following from a temporary component (its outputs)
		//should instead become an output of the actual component.
		//If there is no actual component generated, then the statement
		//is necessarily FALSE and should be replaced by the false
		//component.
		for(GdlSentence sentence : temporaryComponents.keySet()) {
			PolymorphicComponent tempComp = temporaryComponents.get(sentence);
			PolymorphicComponent realComp = components.get(sentence);
			if(realComp == null) {
				realComp = falseComponent;
			}
			for(PolymorphicComponent output : tempComp.getOutputs()) {
				//Disconnect
				output.removeInput(tempComp);
				//tempComp.removeOutput(output); //do at end
				//Connect
				output.addInput(realComp);
				realComp.addOutput(output);
			}
			tempComp.removeAllOutputs();

			if(temporaryNegations.containsKey(sentence)) {
				//Should be pointing to a "not" that now gets input from realComp
				//Should be fine to put into negations
				negations.put(sentence, temporaryNegations.get(sentence));
				//If this follows true/false, will get resolved by the next set of optimizations
			}

			optimizeAwayTrueAndFalse(components, negations, trueComponent, falseComponent);

		}
	}

	/**
	 * Components and negations may be null, if e.g. this is a post-optimization.
	 * TrueComponent and falseComponent are required.
	 *
	 * Doesn't actually work that way... shoot. Need something that will remove the
	 * component from the propnet entirely.
	 * @throws InterruptedException
	 */
	private static void optimizeAwayTrueAndFalse(Map<GdlSentence, PolymorphicComponent> components, Map<GdlSentence, PolymorphicComponent> negations, PolymorphicComponent trueComponent, PolymorphicComponent falseComponent) throws InterruptedException {
	    while(hasNonessentialChildren(trueComponent, false) || hasNonessentialChildren(falseComponent, true)) {
	    	ConcurrencyUtils.checkForInterruption();
            optimizeAwayTrue(components, negations, null, trueComponent, falseComponent);
            optimizeAwayFalse(components, negations, null, trueComponent, falseComponent);
        }
	}

	private static void optimizeAwayTrueAndFalse(PolymorphicPropNet pn, PolymorphicComponent trueComponent, PolymorphicComponent falseComponent) {
	    while(hasNonessentialChildren(trueComponent, false) || hasNonessentialChildren(falseComponent, true)) {
	        optimizeAwayTrue(null, null, pn, trueComponent, falseComponent);
	        optimizeAwayFalse(null, null, pn, trueComponent, falseComponent);
	    }
	}

	private static Set<PolymorphicComponent> findImmediatelyNonEssentialChildren(PolymorphicComponent parent, boolean forFalse)
	{
		Set<PolymorphicComponent> result = new HashSet<PolymorphicComponent>();
		
		for(PolymorphicComponent c : parent.getOutputs())
		{
			if ( !isEssentialProposition(c, forFalse) && !(c instanceof PolymorphicTransition))
			{
				result.add(c);
			}
		}
		return result;
	}
	
	//TODO: Create a version with just a set of components that we can share with post-optimizations
	private static void optimizeAwayFalse(
			Map<GdlSentence, PolymorphicComponent> components, Map<GdlSentence, PolymorphicComponent> negations, PolymorphicPropNet pn, PolymorphicComponent trueComponent,
			PolymorphicComponent falseComponent) {
        assert((components != null && negations != null) || pn != null);
        assert((components == null && negations == null) || pn == null);
        
        Set<PolymorphicComponent> nonEssentials;
        
		//while(hasNonessentialChildren(falseComponent)) {
		while((nonEssentials = findImmediatelyNonEssentialChildren(falseComponent, true)).size() != 0) {
			//System.out.println("Found " + nonEssentials.size() + " immediately non-essential children, out of a total of " + falseComponent.getOutputs().size());
			//int count = 0;
			for(PolymorphicComponent output : nonEssentials)
			{
				//if ( count++ % 1000 == 0)
				//{
				//	System.out.println("...processed " + count + " of " + nonEssentials.size());
				//}
			//Iterator<Component> outputItr = falseComponent.getOutputs().iterator();
			//Component output = outputItr.next();
			//while(isEssentialProposition(output) || output instanceof Transition) {
			//    if(outputItr.hasNext())
			//        output = outputItr.next();
			//    else
			//        return;
			//}
			if(output instanceof PolymorphicProposition) {
				//Move its outputs to be outputs of false
				for(PolymorphicComponent child : output.getOutputs()) {
					//Disconnect
					child.removeInput(output);
					//output.removeOutput(child); //do at end
					//Reconnect; will get children before returning, if nonessential
					falseComponent.addOutput(child);
					child.addInput(falseComponent);
				}
				output.removeAllOutputs();

				if(!isEssentialProposition(output, true)) {
					PolymorphicProposition prop = (PolymorphicProposition) output;
					//Remove the proposition entirely
					falseComponent.removeOutput(output);
					output.removeInput(falseComponent);
					//Update its location to the trueComponent in our map
					if(components != null) {
					    components.put(prop.getName(), falseComponent);
					    negations.put(prop.getName(), trueComponent);
					} else {
					    pn.removeComponent(output);
					}
				}
			} else if(output instanceof PolymorphicAnd) {
				PolymorphicAnd and = (PolymorphicAnd) output;
				and.removeInput(falseComponent);
				falseComponent.removeOutput(and);
				//Attach children of and to falseComponent
				for(PolymorphicComponent child : and.getOutputs()) {
					child.addInput(falseComponent);
					falseComponent.addOutput(child);
					child.removeInput(and);
				}
				//Disconnect and completely
				and.removeAllOutputs();
				for(PolymorphicComponent parent : and.getInputs())
					parent.removeOutput(and);
				and.removeAllInputs();
				if(pn != null)
				    pn.removeComponent(and);
			} else if(output instanceof PolymorphicOr) {
				PolymorphicOr or = (PolymorphicOr) output;
				//Remove as input from or
				or.removeInput(falseComponent);
				falseComponent.removeOutput(or);
				//If or has only one input, remove it
				if(or.getInputs().size() == 1) {
					PolymorphicComponent in = or.getSingleInput();
					or.removeInput(in);
					in.removeOutput(or);
					for(PolymorphicComponent out : or.getOutputs()) {
						//Disconnect from and
						out.removeInput(or);
						//or.removeOutput(out); //do at end
						//Connect directly to the new input
						out.addInput(in);
						in.addOutput(out);
					}
					or.removeAllOutputs();
					if(pn != null)
					    pn.removeComponent(or);
				}
			} else if(output instanceof PolymorphicNot) {
				PolymorphicNot not = (PolymorphicNot) output;
				//Disconnect from falseComponent
				not.removeInput(falseComponent);
				falseComponent.removeOutput(not);
				//Connect all children of the not to trueComponent
				for(PolymorphicComponent child : not.getOutputs()) {
					//Disconnect
					child.removeInput(not);
					//not.removeOutput(child); //Do at end
					//Connect to trueComponent
					child.addInput(trueComponent);
					trueComponent.addOutput(child);
				}
				not.removeAllOutputs();
				if(pn != null)
				    pn.removeComponent(not);
			} else if(output instanceof PolymorphicTransition) {
				//???
				System.err.println("Fix optimizeAwayFalse's case for Transitions");
			}
			}
		}
	}


	private static void optimizeAwayTrue(
			Map<GdlSentence, PolymorphicComponent> components, Map<GdlSentence, PolymorphicComponent> negations, PolymorphicPropNet pn, PolymorphicComponent trueComponent,
			PolymorphicComponent falseComponent) {
	    assert((components != null && negations != null) || pn != null);
	    
        Set<PolymorphicComponent> nonEssentials;
        
		//while(hasNonessentialChildren(trueComponent)) {
		while((nonEssentials = findImmediatelyNonEssentialChildren(trueComponent, false)).size() != 0) {
			//System.out.println("Found " + nonEssentials.size() + " immediately non-essential children, out of a total of " + falseComponent.getOutputs().size());
			//int count = 0;
			for(PolymorphicComponent output : nonEssentials)
			{
			//Iterator<Component> outputItr = trueComponent.getOutputs().iterator();
			//Component output = outputItr.next();
			//while(isEssentialProposition(output) || output instanceof Transition) {
			//	if (outputItr.hasNext())
			//		output = outputItr.next();
			//	else
			//		return;
			//}
			if(output instanceof PolymorphicProposition) {
				//Move its outputs to be outputs of true
				for(PolymorphicComponent child : output.getOutputs()) {
					//Disconnect
					child.removeInput(output);
					//output.removeOutput(child); //do at end
					//Reconnect; will get children before returning, if nonessential
					trueComponent.addOutput(child);
					child.addInput(trueComponent);
				}
				output.removeAllOutputs();

				if(!isEssentialProposition(output, false)) {
					PolymorphicProposition prop = (PolymorphicProposition) output;
					//Remove the proposition entirely
					trueComponent.removeOutput(output);
					output.removeInput(trueComponent);
					//Update its location to the trueComponent in our map
					if(components != null) {
					    components.put(prop.getName(), trueComponent);
					    negations.put(prop.getName(), falseComponent);
					} else {
					    pn.removeComponent(output);
					}
				}
			} else if(output instanceof PolymorphicOr) {
				PolymorphicOr or = (PolymorphicOr) output;
				or.removeInput(trueComponent);
				trueComponent.removeOutput(or);
				//Attach children of or to trueComponent
				for(PolymorphicComponent child : or.getOutputs()) {
					child.addInput(trueComponent);
					trueComponent.addOutput(child);
					child.removeInput(or);
				}
				//Disconnect or completely
				or.removeAllOutputs();
				for(PolymorphicComponent parent : or.getInputs())
					parent.removeOutput(or);
				or.removeAllInputs();
				if(pn != null)
				    pn.removeComponent(or);
			} else if(output instanceof PolymorphicAnd) {
				PolymorphicAnd and = (PolymorphicAnd) output;
				//Remove as input from and
				and.removeInput(trueComponent);
				trueComponent.removeOutput(and);
				//If and has only one input, remove it
				if(and.getInputs().size() == 1) {
					PolymorphicComponent in = and.getSingleInput();
					and.removeInput(in);
					in.removeOutput(and);
					for(PolymorphicComponent out : and.getOutputs()) {
						//Disconnect from and
						out.removeInput(and);
						//and.removeOutput(out); //do at end
						//Connect directly to the new input
						out.addInput(in);
						in.addOutput(out);
					}
					and.removeAllOutputs();
					if(pn != null)
					    pn.removeComponent(and);
				}
			} else if(output instanceof PolymorphicNot) {
				PolymorphicNot not = (PolymorphicNot) output;
				//Disconnect from trueComponent
				not.removeInput(trueComponent);
				trueComponent.removeOutput(not);
				//Connect all children of the not to falseComponent
				for(PolymorphicComponent child : not.getOutputs()) {
					//Disconnect
					child.removeInput(not);
					//not.removeOutput(child); //Do at end
					//Connect to falseComponent
					child.addInput(falseComponent);
					falseComponent.addOutput(child);
				}
				not.removeAllOutputs();
				if(pn != null)
				    pn.removeComponent(not);
			} else if(output instanceof PolymorphicTransition) {
				//???
				System.err.println("Fix optimizeAwayTrue's case for Transitions");
			}
			}
		}
	}


	private static boolean hasNonessentialChildren(PolymorphicComponent trueComponent, boolean forFalse) {
		for(PolymorphicComponent child : trueComponent.getOutputs()) {
			if(child instanceof PolymorphicTransition)
				continue;
			if(!isEssentialProposition(child, forFalse))
				return true;
			//We don't want any grandchildren, either
			//	SD - this breaks down in certain case leading to infinite loops - for
			//	example when an isLegal is used as input to other parts of the state
			//	calculation (crossers3 is an example such ruleset)
			//if(!child.getOutputs().isEmpty())
			//	return true;
		}
		return false;
	}


	private static boolean isEssentialProposition(PolymorphicComponent component, boolean forFalse) {
		if(!(component instanceof PolymorphicProposition))
			return false;

		//We're looking for things that would be outputs of "true" or "false",
		//but we would still want to keep as propositions to be read by the
		//state machine
		PolymorphicProposition prop = (PolymorphicProposition) component;
		GdlConstant name = prop.getName().getName();

		return (name.equals(LEGAL) && !forFalse) /*|| name.equals(NEXT)*/ || name.equals(GOAL)
				|| name.equals(INIT) || name.equals(TERMINAL);
	}


	private static void completeComponentSet(Set<PolymorphicComponent> componentSet) {
		Set<PolymorphicComponent> newComponents = new HashSet<PolymorphicComponent>();
		Set<PolymorphicComponent> componentsToTry = new HashSet<PolymorphicComponent>(componentSet);
		while(!componentsToTry.isEmpty()) {
			for(PolymorphicComponent c : componentsToTry) {
				for(PolymorphicComponent out : c.getOutputs()) {
					if(!componentSet.contains(out))
						newComponents.add(out);
				}
				for(PolymorphicComponent in : c.getInputs()) {
					if(!componentSet.contains(in))
						newComponents.add(in);
				}
			}
			componentSet.addAll(newComponents);
			componentsToTry = newComponents;
			newComponents = new HashSet<PolymorphicComponent>();
		}
	}


	private static void addTransitions(Map<GdlSentence, PolymorphicComponent> components, PolymorphicComponentFactory componentFactory) {
		for(Entry<GdlSentence, PolymorphicComponent> entry : components.entrySet()) {
			GdlSentence sentence = entry.getKey();

			if(sentence.getName().equals(NEXT)) {
				//connect to true
				GdlSentence trueSentence = GdlPool.getRelation(TRUE, sentence.getBody());
				PolymorphicComponent nextComponent = entry.getValue();
				PolymorphicComponent trueComponent = components.get(trueSentence);
				//There might be no true component (for example, because the bases
				//told us so). If that's the case, don't have a transition.
				if(trueComponent == null) {
				    // Skipping transition to supposedly impossible 'trueSentence'
				    continue;
				}
				PolymorphicTransition transition = componentFactory.createTransition(-1);
				transition.addInput(nextComponent);
				nextComponent.addOutput(transition);
				transition.addOutput(trueComponent);
				trueComponent.addInput(transition);
			}
		}
	}

	//TODO: Replace with version using constantChecker only
	//TODO: This can give problematic results if interpreted in
	//the standard way (see test_case_3d)
	private static void setUpInit(Map<GdlSentence, PolymorphicComponent> components,
			PolymorphicConstant trueComponent, PolymorphicConstant falseComponent,
			PolymorphicComponentFactory componentFactory) {
		PolymorphicProposition initProposition = componentFactory.createProposition(-1, GdlPool.getProposition(INIT_CAPS));
		for(Entry<GdlSentence, PolymorphicComponent> entry : components.entrySet()) {
			//Is this something that will be true?
			if(entry.getValue() == trueComponent) {
				if(entry.getKey().getName().equals(INIT)) {
					//Find the corresponding true sentence
					GdlSentence trueSentence = GdlPool.getRelation(TRUE, entry.getKey().getBody());
					//System.out.println("True sentence from init: " + trueSentence);
					PolymorphicComponent trueSentenceComponent = components.get(trueSentence);
					if(trueSentenceComponent.getInputs().isEmpty()) {
						//Case where there is no transition input
						//Add the transition input, connect to init, continue loop
						PolymorphicTransition transition = componentFactory.createTransition(-1);
						//init goes into transition
						transition.addInput(initProposition);
						initProposition.addOutput(transition);
						//transition goes into component
						trueSentenceComponent.addInput(transition);
						transition.addOutput(trueSentenceComponent);
					} else {
						//The transition already exists
						PolymorphicComponent transition = trueSentenceComponent.getSingleInput();

						//We want to add init as a thing that precedes the transition
						//Disconnect existing input
						PolymorphicComponent input = transition.getSingleInput();
						//input and init go into or, or goes into transition
						input.removeOutput(transition);
						transition.removeInput(input);
						List<PolymorphicComponent> orInputs = new ArrayList<PolymorphicComponent>(2);
						orInputs.add(input);
						orInputs.add(initProposition);
						orify(orInputs, transition, falseComponent, componentFactory);
					}
				}
			}
		}
	}

	/**
	 * Adds an or gate connecting the inputs to produce the output.
	 * Handles special optimization cases like a true/false input.
	 */
	private static void orify(Collection<PolymorphicComponent> inputs, PolymorphicComponent output, PolymorphicConstant falseProp, PolymorphicComponentFactory componentFactory) {
		//TODO: Look for already-existing ors with the same inputs?
		//Or can this be handled with a GDL transformation?

		//Special case: An input is the true constant
		for(PolymorphicComponent in : inputs) {
			if(in instanceof PolymorphicConstant && in.getValue()) {
				//True constant: connect that to the component, done
				in.addOutput(output);
				output.addInput(in);
				return;
			}
		}

		//Special case: An input is "or"
		//I'm honestly not sure how to handle special cases here...
		//What if that "or" gate has multiple outputs? Could that happen?

		//For reals... just skip over any false constants
		PolymorphicOr or = componentFactory.createOr(-1,-1);
		for(PolymorphicComponent in : inputs) {
			if(!(in instanceof PolymorphicConstant)) {
				in.addOutput(or);
				or.addInput(in);
			}
		}
		//What if they're all false? (Or inputs is empty?) Then no inputs at this point...
		if(or.getInputs().isEmpty()) {
			//Hook up to "false"
			falseProp.addOutput(output);
			output.addInput(falseProp);
			return;
		}
		//If there's just one, on the other hand, don't use the or gate
		if(or.getInputs().size() == 1) {
			PolymorphicComponent in = or.getSingleInput();
			in.removeOutput(or);
			or.removeInput(in);
			in.addOutput(output);
			output.addInput(in);
			return;
		}
		or.addOutput(output);
		output.addInput(or);
	}

	//TODO: This code is currently used by multiple classes, so perhaps it should be
	//factored out into the SentenceModel.
	private static List<SentenceForm> getTopologicalOrdering(
			Set<SentenceForm> forms,
			Multimap<SentenceForm, SentenceForm> dependencyGraph, boolean usingBase, boolean usingInput) throws InterruptedException {
		//We want each form as a key of the dependency graph to
		//follow all the forms in the dependency graph, except maybe itself
		Queue<SentenceForm> queue = new LinkedList<SentenceForm>(forms);
		List<SentenceForm> ordering = new ArrayList<SentenceForm>(forms.size());
		Set<SentenceForm> alreadyOrdered = new HashSet<SentenceForm>();
		while(!queue.isEmpty()) {
			SentenceForm curForm = queue.remove();
			boolean readyToAdd = true;
			//Don't add if there are dependencies
			for(SentenceForm dependency : dependencyGraph.get(curForm)) {
				if(!dependency.equals(curForm) && !alreadyOrdered.contains(dependency)) {
					readyToAdd = false;
					break;
				}
			}
			//Don't add if it's true/next/legal/does and we're waiting for base/input
			if(usingBase && (curForm.getName().equals(TRUE) || curForm.getName().equals(NEXT) || curForm.getName().equals(INIT))) {
				//Have we added the corresponding base sf yet?
				SentenceForm baseForm = curForm.withName(BASE);
				if(!alreadyOrdered.contains(baseForm)) {
					readyToAdd = false;
				}
			}
			if(usingInput && (curForm.getName().equals(DOES) || curForm.getName().equals(LEGAL))) {
				SentenceForm inputForm = curForm.withName(INPUT);
				if(!alreadyOrdered.contains(inputForm)) {
					readyToAdd = false;
				}
			}
			//Add it
			if(readyToAdd) {
				ordering.add(curForm);
				alreadyOrdered.add(curForm);
			} else {
				queue.add(curForm);
			}
			//TODO: Add check for an infinite loop here, or stratify loops

			ConcurrencyUtils.checkForInterruption();
		}
		return ordering;
	}

	private static void addSentenceForm(SentenceForm form, SentenceDomainModel model,
			Map<GdlSentence, PolymorphicComponent> components,
			Map<GdlSentence, PolymorphicComponent> negations,
			PolymorphicConstant trueComponent, PolymorphicConstant falseComponent,
			boolean usingBase, boolean usingInput,
			Set<SentenceForm> recursionForms,
			Map<GdlSentence, PolymorphicComponent> temporaryComponents, Map<GdlSentence, PolymorphicComponent> temporaryNegations,
			Map<SentenceForm, FunctionInfo> functionInfoMap, ConstantChecker constantChecker,
			Map<SentenceForm, Collection<GdlSentence>> completedSentenceFormValues,
			PolymorphicComponentFactory componentFactory) throws InterruptedException {
		//This is the meat of it (along with the entire Assignments class).
		//We need to enumerate the possible propositions in the sentence form...
		//We also need to hook up the sentence form to the inputs that can make it true.
		//We also try to optimize as we go, which means possibly removing the
		//proposition if it isn't actually possible, or replacing it with
		//true/false if it's a constant.

		Set<GdlSentence> alwaysTrueSentences = model.getSentencesListedAsTrue(form);
		Set<GdlRule> rules = model.getRules(form);

		for(GdlSentence alwaysTrueSentence : alwaysTrueSentences) {
			//We add the sentence as a constant
			if(alwaysTrueSentence.getName().equals(LEGAL)
					|| alwaysTrueSentence.getName().equals(NEXT)
					|| alwaysTrueSentence.getName().equals(GOAL)) {
				PolymorphicProposition prop =  componentFactory.createProposition(-1, alwaysTrueSentence);
				//Attach to true
				trueComponent.addOutput(prop);
				prop.addInput(trueComponent);
				//Still want the same components;
				//we just don't want this to be anonymized
			}
			//Assign as true
			components.put(alwaysTrueSentence, trueComponent);
			negations.put(alwaysTrueSentence, falseComponent);
			continue;
		}

		//For does/true, make nodes based on input/base, if available
		if(usingInput && form.getName().equals(DOES)) {
			//Add only those propositions for which there is a corresponding INPUT
			SentenceForm inputForm = form.withName(INPUT);
			for (GdlSentence inputSentence : constantChecker.getTrueSentences(inputForm)) {
				GdlSentence doesSentence = GdlPool.getRelation(DOES, inputSentence.getBody());
				PolymorphicProposition prop = componentFactory.createProposition(-1, doesSentence);
				components.put(doesSentence, prop);
			}
			return;
		}
		if(usingBase && form.getName().equals(TRUE)) {
			SentenceForm baseForm = form.withName(BASE);
			for (GdlSentence baseSentence : constantChecker.getTrueSentences(baseForm)) {
				GdlSentence trueSentence = GdlPool.getRelation(TRUE, baseSentence.getBody());
				PolymorphicProposition prop = componentFactory.createProposition(-1, trueSentence);
				components.put(trueSentence, prop);
			}
			return;
		}

		Map<GdlSentence, Set<PolymorphicComponent>> inputsToOr = new HashMap<GdlSentence, Set<PolymorphicComponent>>();
		for(GdlRule rule : rules) {
			Assignments assignments = AssignmentsFactory.getAssignmentsForRule(rule, model, functionInfoMap, completedSentenceFormValues);

			//Calculate vars in live (non-constant, non-distinct) conjuncts
			Set<GdlVariable> varsInLiveConjuncts = getVarsInLiveConjuncts(rule, constantChecker.getConstantSentenceForms());
			varsInLiveConjuncts.addAll(GdlUtils.getVariables(rule.getHead()));
			Set<GdlVariable> varsInRule = new HashSet<GdlVariable>(GdlUtils.getVariables(rule));
			boolean preventDuplicatesFromConstants =
				(varsInRule.size() > varsInLiveConjuncts.size());

			//Do we just pass those to the Assignments class in that case?
			for(AssignmentIterator asnItr = assignments.getIterator(); asnItr.hasNext(); ) {
				Map<GdlVariable, GdlConstant> assignment = asnItr.next();
				if(assignment == null) continue; //Not sure if this will ever happen

				ConcurrencyUtils.checkForInterruption();

				GdlSentence sentence = CommonTransforms.replaceVariables(rule.getHead(), assignment);

				//Now we go through the conjuncts as before, but we wait to hook them up.
				List<PolymorphicComponent> componentsToConnect = new ArrayList<PolymorphicComponent>(rule.arity());
				for(GdlLiteral literal : rule.getBody()) {
					if(literal instanceof GdlSentence) {
						//Get the sentence post-substitutions
						GdlSentence transformed = CommonTransforms.replaceVariables((GdlSentence) literal, assignment);

						//Check for constant-ness
						SentenceForm conjunctForm = model.getSentenceForm(transformed);
						if(constantChecker.isConstantForm(conjunctForm)) {
							if(!constantChecker.isTrueConstant(transformed)) {
								List<GdlVariable> varsToChange = getVarsInConjunct(literal);
								asnItr.changeOneInNext(varsToChange, assignment);
								componentsToConnect.add(null);
							}
							continue;
						}

						PolymorphicComponent conj = components.get(transformed);
						//If conj is null and this is a sentence form we're still handling,
						//hook up to a temporary sentence form
						if(conj == null) {
							conj = temporaryComponents.get(transformed);
						}
						if(conj == null && SentenceModelUtils.inSentenceFormGroup(transformed, recursionForms)) {
							//Set up a temporary component
							PolymorphicProposition tempProp = componentFactory.createProposition(-1, transformed);
							temporaryComponents.put(transformed, tempProp);
							conj = tempProp;
						}
						//Let's say this is false; we want to backtrack and change the right variable
						if(conj == null || isThisConstant(conj, falseComponent)) {
							List<GdlVariable> varsInConjunct = getVarsInConjunct(literal);
							asnItr.changeOneInNext(varsInConjunct, assignment);
							//These last steps just speed up the process
							//telling the factory to ignore this rule
							componentsToConnect.add(null);
							continue; //look at all the other restrictions we'll face
						}

						componentsToConnect.add(conj);
					} else if(literal instanceof GdlNot) {
						//Add a "not" if necessary
						//Look up the negation
						GdlSentence internal = (GdlSentence) ((GdlNot) literal).getBody();
						GdlSentence transformed = CommonTransforms.replaceVariables(internal, assignment);

						//Add constant-checking here...
						SentenceForm conjunctForm = model.getSentenceForm(transformed);
						if(constantChecker.isConstantForm(conjunctForm)) {
							if(constantChecker.isTrueConstant(transformed)) {
								List<GdlVariable> varsToChange = getVarsInConjunct(literal);
								asnItr.changeOneInNext(varsToChange, assignment);
								componentsToConnect.add(null);
							}
							continue;
						}

						PolymorphicComponent conj = negations.get(transformed);
						if(isThisConstant(conj, falseComponent)) {
							//We need to change one of the variables inside
							List<GdlVariable> varsInConjunct = getVarsInConjunct(internal);
							asnItr.changeOneInNext(varsInConjunct, assignment);
							//ignore this rule
							componentsToConnect.add(null);
							continue;
						}
						if(conj == null) {
							conj = temporaryNegations.get(transformed);
						}
						//Check for the recursive case:
						if(conj == null && SentenceModelUtils.inSentenceFormGroup(transformed, recursionForms)) {
							PolymorphicComponent positive = components.get(transformed);
							if(positive == null) {
								positive = temporaryComponents.get(transformed);
							}
							if(positive == null) {
								//Make the temporary proposition
								PolymorphicProposition tempProp = componentFactory.createProposition(-1, transformed);
								temporaryComponents.put(transformed, tempProp);
								positive = tempProp;
							}
							//Positive is now set and in temporaryComponents
							//Evidently, wasn't in temporaryNegations
							//So we add the "not" gate and set it in temporaryNegations
							PolymorphicNot not = componentFactory.createNot(-1);
							//Add positive as input
							not.addInput(positive);
							positive.addOutput(not);
							temporaryNegations.put(transformed, not);
							conj = not;
						}
						if(conj == null) {
							PolymorphicComponent positive = components.get(transformed);
							//No, because then that will be attached to "negations", which could be bad

							if(positive == null) {
								//So the positive can't possibly be true (unless we have recurstion)
								//and so this would be positive always
								//We want to just skip this conjunct, so we continue to the next

								continue; //to the next conjunct
							}

							//Check if we're sharing a component with another sentence with a negation
							//(i.e. look for "nots" in our outputs and use those instead)
							PolymorphicNot existingNotOutput = getNotOutput(positive);
							if(existingNotOutput != null) {
								componentsToConnect.add(existingNotOutput);
								negations.put(transformed, existingNotOutput);
								continue; //to the next conjunct
							}

							PolymorphicNot not = componentFactory.createNot(-1);
							not.addInput(positive);
							positive.addOutput(not);
							negations.put(transformed, not);
							conj = not;
						}
						componentsToConnect.add(conj);
					} else if(literal instanceof GdlDistinct) {
						//Already handled; ignore
					} else {
						throw new RuntimeException("Unwanted GdlLiteral type");
					}
				}
				if(!componentsToConnect.contains(null)) {
					//Connect all the components
					PolymorphicProposition andComponent = componentFactory.createProposition(-1,TEMP);

					andify(componentsToConnect, andComponent, trueComponent, componentFactory);
					if(!isThisConstant(andComponent, falseComponent)) {
						if(!inputsToOr.containsKey(sentence))
							inputsToOr.put(sentence, new HashSet<PolymorphicComponent>());
						inputsToOr.get(sentence).add(andComponent);
						//We'll want to make sure at least one of the non-constant
						//components is changing
						if(preventDuplicatesFromConstants) {
							asnItr.changeOneInNext(varsInLiveConjuncts, assignment);
						}
					}
				}
			}
		}

		//At the end, we hook up the conjuncts
		for(Entry<GdlSentence, Set<PolymorphicComponent>> entry : inputsToOr.entrySet()) {
			ConcurrencyUtils.checkForInterruption();

			GdlSentence sentence = entry.getKey();
			Set<PolymorphicComponent> inputs = entry.getValue();
			Set<PolymorphicComponent> realInputs = new HashSet<PolymorphicComponent>();
			for(PolymorphicComponent input : inputs) {
				if(input instanceof PolymorphicConstant || input.getInputs().size() == 0) {
					realInputs.add(input);
				} else {
					realInputs.add(input.getSingleInput());
					input.getSingleInput().removeOutput(input);
					input.removeAllInputs();
				}
			}

			PolymorphicProposition prop = componentFactory.createProposition(-1, sentence);
			orify(realInputs, prop, falseComponent, componentFactory);
			components.put(sentence, prop);
		}

		//True/does sentences will have none of these rules, but
		//still need to exist/"float"
		//We'll do this if we haven't used base/input as a basis
		if(form.getName().equals(TRUE)
				|| form.getName().equals(DOES)) {
			for(GdlSentence sentence : model.getDomain(form)) {
				ConcurrencyUtils.checkForInterruption();

				PolymorphicProposition prop = componentFactory.createProposition(-1, sentence);
				components.put(sentence, prop);
			}
		}

	}


	private static Set<GdlVariable> getVarsInLiveConjuncts(
			GdlRule rule, Set<SentenceForm> constantSentenceForms) {
		Set<GdlVariable> result = new HashSet<GdlVariable>();
		for(GdlLiteral literal : rule.getBody()) {
			if(literal instanceof GdlRelation) {
				if(!SentenceModelUtils.inSentenceFormGroup((GdlRelation)literal, constantSentenceForms))
					result.addAll(GdlUtils.getVariables(literal));
			} else if(literal instanceof GdlNot) {
				GdlNot not = (GdlNot) literal;
				GdlSentence inner = (GdlSentence) not.getBody();
				if(!SentenceModelUtils.inSentenceFormGroup(inner, constantSentenceForms))
					result.addAll(GdlUtils.getVariables(literal));
			}
		}
		return result;
	}

	private static boolean isThisConstant(PolymorphicComponent conj, PolymorphicConstant constantComponent) {
		if(conj == constantComponent)
			return true;
		return (conj instanceof PolymorphicProposition && conj.getInputs().size() == 1 && conj.getSingleInput() == constantComponent);
	}


	private static PolymorphicNot getNotOutput(PolymorphicComponent positive) {
		for(PolymorphicComponent c : positive.getOutputs()) {
			if(c instanceof PolymorphicNot) {
				return (PolymorphicNot) c;
			}
		}
		return null;
	}


	private static List<GdlVariable> getVarsInConjunct(GdlLiteral literal) {
		return GdlUtils.getVariables(literal);
	}


	private static void andify(List<PolymorphicComponent> inputs, PolymorphicComponent output, PolymorphicConstant trueProp, PolymorphicComponentFactory componentFactory) {
		//Special case: If the inputs include false, connect false to thisComponent
		for(PolymorphicComponent c : inputs) {
			if(c instanceof PolymorphicConstant && !c.getValue()) {
				//Connect false (c) to the output
				output.addInput(c);
				c.addOutput(output);
				return;
			}
		}

		//For reals... just skip over any true constants
		PolymorphicAnd and = componentFactory.createAnd(-1,-1);
		for(PolymorphicComponent in : inputs) {
			if(!(in instanceof PolymorphicConstant)) {
				in.addOutput(and);
				and.addInput(in);
			}
		}
		//What if they're all true? (Or inputs is empty?) Then no inputs at this point...
		if(and.getInputs().isEmpty()) {
			//Hook up to "true"
			trueProp.addOutput(output);
			output.addInput(trueProp);
			return;
		}
		//If there's just one, on the other hand, don't use the and gate
		if(and.getInputs().size() == 1) {
			PolymorphicComponent in = and.getSingleInput();
			in.removeOutput(and);
			and.removeInput(in);
			in.addOutput(output);
			output.addInput(in);
			return;
		}
		and.addOutput(output);
		output.addInput(and);
	}

	/**
	 * Currently requires the init propositions to be left in the graph.
	 * @param pn
	 */
	static enum Type { STAR(false, false, "grey"),
	    TRUE(true, false, "green"),
	    FALSE(false, true, "red"),
	    BOTH(true, true, "white");
	private final boolean hasTrue;
	private final boolean hasFalse;
	private final String color;

	Type(boolean hasTrue, boolean hasFalse, String color) {
	    this.hasTrue = hasTrue;
	    this.hasFalse = hasFalse;
	    this.color = color;
	}

	public boolean hasTrue() {
	    return hasTrue;
	}
	public boolean hasFalse() {
	    return hasFalse;
	}

    public String getColor() {
        return color;
    }
	}
	
	static class ReEvaulationSet implements Iterable<PolymorphicComponent>
	{
		private Set<PolymorphicComponent> contents = new LinkedHashSet<PolymorphicComponent>();
		private Map<PolymorphicComponent, Type> reachability;
		
		public ReEvaulationSet(Map<PolymorphicComponent, Type> reachability)
		{
			this.reachability = reachability;
		}
		
		public void add(PolymorphicComponent c)
		{
			if ( c instanceof PolymorphicConstant )
			{
				System.out.println("Constant added to re-eval list!");
			}
			if ( !reachability.containsKey(c))
			{
				System.out.println("Unreachable component to re-eval list!");
			}
			
			contents.add(c);
		}
		
		public void addAll(Collection<? extends PolymorphicComponent> collection)
		{
			for(PolymorphicComponent c : collection)
			{
				add(c);
			}
		}
		
		public void remove(PolymorphicComponent c)
		{
			contents.remove(c);
		}
		
		public Iterator<PolymorphicComponent> iterator()
		{
			return contents.iterator();
		}
		
		public boolean isEmpty()
		{
			return contents.isEmpty();
		}
		
		public int size()
		{
			return contents.size();
		}
	}
	
	public static void removeUnreachableBasesAndInputs(PolymorphicPropNet pn) {
		removeUnreachableBasesAndInputs(pn, false);
	}
		
	public static void removeUnreachableBasesAndInputs(PolymorphicPropNet pn, boolean preserveAllTransitions) {
		PolymorphicComponentFactory componentFactory = pn.getComponentFactory();
	    //This actually might remove more than bases and inputs
	    //We flow through the game graph to see what can be true (and what can be false?)...
	    Map<PolymorphicComponent, Type> reachability = new HashMap<PolymorphicComponent, Type>();
	    Set<GdlTerm> initted = new HashSet<GdlTerm>();
	    for(PolymorphicComponent c : pn.getComponents()) {
	        reachability.put(c, Type.STAR);
	        if(c instanceof PolymorphicProposition) {
	        	PolymorphicProposition p = (PolymorphicProposition) c;
	            if(p.getName() instanceof GdlRelation) {
	                GdlRelation r = (GdlRelation) p.getName();
	                if(r.getName().equals(INIT)) {
	                    //Add the base
	                    initted.add(r.get(0));
	                }
	            }
	        }
	        else if (preserveAllTransitions && c instanceof PolymorphicTransition)
	        {
	        	reachability.put(c, Type.BOTH);
	        }
	    }

        //Set<Component> toReevaluate = new LinkedHashSet<Component>();
	    ReEvaulationSet toReevaluate = new ReEvaulationSet(reachability);
        Set<PolymorphicComponent> explicitlyInitedBases = new HashSet<PolymorphicComponent>();

	    for(PolymorphicComponent c : pn.getComponents()) {
	        //	Descendents of TRUE constants must be considered
	        if(c instanceof PolymorphicConstant && c.getValue()) {
	        	toReevaluate.addAll(c.getOutputs());
	        }
	        //	If we want to preserve transitions regardless of their inputs
	        //	tag them as able to take any vlaue and force reconsideration of their
	        //	children
	        else if (preserveAllTransitions && c instanceof PolymorphicTransition)
	        {
	        	reachability.put(c, Type.BOTH);
	        	toReevaluate.addAll(c.getOutputs());
	        }
	    }
       
        //Every input can be false (we assume that no player will have just one move allowed all game)
        for(PolymorphicProposition p : pn.getInputPropositions().values()) {
            if(pn.getLegalInputMap().containsKey(p))
            {
                reachability.put(p, Type.BOTH);
            }
            else
            {
                reachability.put(p, Type.FALSE);
			}
            toReevaluate.addAll(p.getOutputs());
        }
	    //Every base with "init" can be true, every base without "init" can be false
	    for(Entry<GdlSentence, PolymorphicProposition> entry : pn.getBasePropositions().entrySet()) {
	    	PolymorphicProposition p = entry.getValue();
	        //So, does it have init?
	        //TODO: Remove "true" dereferencing? Need "global" option for that
	        //if(initted.contains(entry.getKey())) {
	        if(entry.getKey() instanceof GdlRelation
	                && initted.contains(((GdlRelation)entry.getKey()).get(0))) {
	            reachability.put(p, Type.TRUE);
	            explicitlyInitedBases.add(p);
	        } else {
	            reachability.put(entry.getValue(), Type.FALSE);
	        }
            toReevaluate.addAll(p.getOutputs());
	    }
	    //Might as well add in INIT
	    PolymorphicProposition initProposition = pn.getInitProposition();
	    if ( initProposition != null )
	    {
	    	reachability.put(initProposition, Type.BOTH);
	    	toReevaluate.addAll(initProposition.getOutputs());
	    }
	    
	    int loopDetectionCount = 0;
	    int reEvaluationLimit = toReevaluate.size();
	    
	    //Now, propagate everything we know
	    while(!toReevaluate.isEmpty()) {
	    	if ( loopDetectionCount++ > reEvaluationLimit )
	    	{
	    		//	We're in a loop of undecided components - just leave anything that's still undecided untrimmed
	    		//	For now we take a REALLY simplistic approach and promote everything caught in the looping to
	    		//	BOTH
	    		//Set<Component> newReEvaulateList = new LinkedHashSet<Component>();
	    		ReEvaulationSet newReEvaulateList = new ReEvaulationSet(reachability);
	    		
	    		for(PolymorphicComponent curComp : toReevaluate)
	    		{
	    	        Type type = reachability.get(curComp);
	    	        type = addTrue(type);
	    	        type = addFalse(type);
	    	        reachability.put(curComp, type);

	    	        newReEvaulateList.addAll(curComp.getOutputs());
	    		}
	    	
	    		toReevaluate = newReEvaulateList;
	        	loopDetectionCount = 0;
	        	reEvaluationLimit = toReevaluate.size();
	    		continue;
	    	}
	    	PolymorphicComponent curComp = toReevaluate.iterator().next();
	        toReevaluate.remove(curComp);
	        //Can we upgrade its type?
	        Type type = reachability.get(curComp);
	        boolean checkTrue = true, checkFalse = true;
	        if(type == Type.BOTH) { //Nope
	            continue;
	        } else if(type == Type.TRUE) {
	            checkTrue = false;
	        } else if(type == Type.FALSE) {
	            checkFalse = false;
	        }
	        boolean upgradeTrue = false, upgradeFalse = false;
	        boolean curCompIsLegalProposition = false;

	        //How we react to the parents (or pseudo-parents) depends on the type
	        Collection<? extends PolymorphicComponent> parents = curComp.getInputs();
	        if(curComp instanceof PolymorphicAnd) {
	            if(checkTrue) {
	                //All parents must be capable of being true
	                boolean allCanBeTrue = true;
	                Set<PolymorphicComponent> starParents = new HashSet<PolymorphicComponent>();
	                for(PolymorphicComponent parent : parents) {
	                    Type parentType = reachability.get(parent);
	                    if (parent instanceof PolymorphicConstant)
	                    {
	                    	parentType = parent.getValue() ? Type.TRUE : Type.FALSE;
	                    }
	                    if ( parentType == Type.STAR )
	                    {
	                    	starParents.add(parent);
	                    }
	                    else if(!parentType.hasTrue()) {
	                        allCanBeTrue = false;
	                        break;
	                    }
	                }
	                if ( starParents.size() > 0 )
	                {
	                	//	Cannot decide yet - add STAR parents for re-evaluation
	                	//	and then ourselves again
	                	toReevaluate.addAll(starParents);
	                	continue;
	                }
	                upgradeTrue = allCanBeTrue;
	            }
	            if(checkFalse) {
	                //Some parent must be capable of being false
	                Set<PolymorphicComponent> starParents = new HashSet<PolymorphicComponent>();
	                for(PolymorphicComponent parent : parents) {
	                    Type parentType = reachability.get(parent);
	                    if (parent instanceof PolymorphicConstant)
	                    {
	                    	parentType = parent.getValue() ? Type.TRUE : Type.FALSE;
	                    }
	                    if ( parentType == Type.STAR )
	                    {
	                    	starParents.add(parent);
	                    }
	                    else if(parentType.hasFalse()) {
	                        upgradeFalse = true;
	                        break;
	                    }
	                }
	                if ( !upgradeFalse && starParents.size() > 0 )
	                {
	                	//	Cannot decide yet - add STAR parents for re-evaluation
	                	//	and then ourselves again
	                	toReevaluate.addAll(starParents);
	                	continue;
	                }
	            }
	        } else if(curComp instanceof PolymorphicOr) {
	            if(checkTrue) {
	                //Some parent must be capable of being true
	                Set<PolymorphicComponent> starParents = new HashSet<PolymorphicComponent>();
	                for(PolymorphicComponent parent : parents) {
	                    Type parentType = reachability.get(parent);
	                    if (parent instanceof PolymorphicConstant)
	                    {
	                    	parentType = parent.getValue() ? Type.TRUE : Type.FALSE;
	                    }
	                    if ( parentType == Type.STAR )
	                    {
	                    	starParents.add(parent);
	                    }
	                    else if(parentType.hasTrue()) {
                            upgradeTrue = true;
                            break;
                        }
	                }
	                if ( !upgradeTrue && starParents.size() > 0 )
	                {
	                	//	Cannot decide yet - add STAR parents for re-evaluation
	                	//	and then ourselves again
	                	toReevaluate.addAll(starParents);
	                	continue;
	                }
	            }
	            if(checkFalse) {
	              //All parents must be capable of being false
                    boolean allCanBeFalse = true;
	                Set<PolymorphicComponent> starParents = new HashSet<PolymorphicComponent>();
                    for(PolymorphicComponent parent : parents) {
                        Type parentType = reachability.get(parent);
	                    if (parent instanceof PolymorphicConstant)
	                    {
	                    	parentType = parent.getValue() ? Type.TRUE : Type.FALSE;
	                    }
	                    if ( parentType == Type.STAR )
	                    {
	                    	starParents.add(parent);
	                    }
	                    else if(!parentType.hasFalse()) {
                            allCanBeFalse = false;
                            break;
                        }
                    }
	                if ( starParents.size() > 0 )
	                {
	                	//	Cannot decide yet - add STAR parents for re-evaluation
	                	//	and then ourselves again
	                	toReevaluate.addAll(starParents);
	                	continue;
	                }
                    upgradeFalse = allCanBeFalse;
	            }
	        } else if(curComp instanceof PolymorphicNot) {
	        	PolymorphicComponent parent = curComp.getSingleInput();
	            Type parentType = reachability.get(parent);
                if (parent instanceof PolymorphicConstant)
                {
                	parentType = parent.getValue() ? Type.TRUE : Type.FALSE;
                }
	            if ( parentType == Type.STAR)
	            {
	            	toReevaluate.add(parent);
	            	continue;
	            }
	            if(checkTrue && parentType.hasFalse())
	                upgradeTrue = true;
	            if(checkFalse && parentType.hasTrue())
	                upgradeFalse = true;
	        } else if(curComp instanceof PolymorphicTransition) {
	        	PolymorphicComponent parent = curComp.getSingleInput();
                Type parentType = reachability.get(parent);
                if (parent instanceof PolymorphicConstant)
                {
                	parentType = parent.getValue() ? Type.TRUE : Type.FALSE;
                }
	            if ( parentType == Type.STAR)
	            {
	            	toReevaluate.add(parent);
	            	continue;
	            }
                if(checkTrue && parentType.hasTrue())
                    upgradeTrue = true;
                if(checkFalse && parentType.hasFalse())
                    upgradeFalse = true;
	        } else if(curComp instanceof PolymorphicProposition) {
	            //TODO: Special case: Inputs
	        	PolymorphicProposition p = (PolymorphicProposition) curComp;
	            if(pn.getLegalInputMap().containsKey(curComp)) {
	                GdlRelation r = (GdlRelation) p.getName();
	                if(r.getName().equals(DOES)) {
	                    //The legal prop. is a pseudo-parent
	                	PolymorphicComponent legal = pn.getLegalInputMap().get(curComp);
	                    Type legalType = reachability.get(legal);
	                    if(checkTrue && legalType.hasTrue())
	                        upgradeTrue = true;
	                    if(checkFalse && legalType.hasFalse())
	                        upgradeFalse = true;
	                } else {
	                    curCompIsLegalProposition = true;
	                }
	            }

	            //Otherwise, just do same as Transition... easy
	            if(curComp.getInputs().size() == 1) {
	            	PolymorphicComponent parent = curComp.getSingleInput();
	                Type parentType = reachability.get(parent);
                    if (parent instanceof PolymorphicConstant)
                    {
                    	parentType = parent.getValue() ? Type.TRUE : Type.FALSE;
                    }
		            if ( parentType == Type.STAR)
		            {
		            	toReevaluate.add(parent);
		            	continue;
		            }
	                if(checkTrue && parentType.hasTrue())
	                    upgradeTrue = true;
	                if(checkFalse && parentType.hasFalse())
	                    upgradeFalse = true;
	            }
	        } else {
	            //Constants won't get added
	            throw new RuntimeException("Unexpected node type " + curComp.getClass());
	        }

	        //Deal with upgrades
	        if(upgradeTrue) {
	            type = addTrue(type);
	            reachability.put(curComp, type);
	        }
	        if(upgradeFalse) {
	            type = addFalse(type);
	            reachability.put(curComp, type);
	        }
	        if(upgradeTrue || upgradeFalse) {
	            toReevaluate.addAll(curComp.getOutputs());
	            //Don't forget: if "legal", check "does"
	            if(curCompIsLegalProposition) {
	                toReevaluate.add(pn.getLegalInputMap().get(curComp));
	            }
	        	loopDetectionCount = 0;
	        	reEvaluationLimit = toReevaluate.size();
	        }

	    }

	    //We deliberately shouldn't remove the stuff attached to TRUE... or anything that's
	    //always true...
	    //But we should be able to remove bases and inputs (when it's justified)

	    //What can we conclude? Let's dump here
	    /*for(Entry<Component, Type> entry : reachability.entrySet()) {
	        //System.out.println("  "+entry.getKey()+": "+entry.getValue());
	        //We can actually dump a version of the PN with colored nodes in DOT form...
	        System.out.println(entry.getKey().toString().replaceAll("fillcolor=[a-z]+,", "fillcolor="+entry.getValue().getColor()+","));
	    }*/
	    //TODO: Go through all the cases of everything I can dump
	    //For now... how about inputs?
	    PolymorphicConstant trueConst = componentFactory.createConstant(-1, true);
	    PolymorphicConstant alternateTrueConst = componentFactory.createConstant(-1, true);
	    PolymorphicConstant falseConst = componentFactory.createConstant(-1, false);
	    pn.addComponent(trueConst);
	    pn.addComponent(alternateTrueConst);	//	Used for nodes we need to keep but which are always true
	    pn.addComponent(falseConst);
	    //Make them the input of all false/true components
	    for(Entry<PolymorphicComponent, Type> entry : reachability.entrySet()) {
	        Type type = entry.getValue();
	        if(type == Type.TRUE || type == Type.FALSE) {
	        	PolymorphicComponent c = entry.getKey();
	            //Disconnect from inputs
	            for(PolymorphicComponent input : c.getInputs()) {
	                input.removeOutput(c);
	            }
	            c.removeAllInputs();
	            if((type == Type.TRUE) != (c instanceof PolymorphicNot)) {
	            	if ( explicitlyInitedBases.contains(c))
	            	{
	            		//	Cannot dump bases that are init'd else they'll be missing from the state
	            		//	in which they should appear essentially as a fixture - hook them up to
	            		//	a constant TRUE that we will not trim descendants of
		                c.addInput(alternateTrueConst);
		                alternateTrueConst.addOutput(c);	            		
	            	}
	            	else
	            	{
		                c.addInput(trueConst);
		                trueConst.addOutput(c);
					}
	            } else {
                    c.addInput(falseConst);
                    falseConst.addOutput(c);
	            }
	        }
	    }
	    
	    //then...
	    //optimizeAwayTrueAndFalse(null, null, trueConst, falseConst);
	    optimizeAwayTrueAndFalse(pn, trueConst, falseConst);
	    
	}
	
	private static Type addTrue(Type type) {
        switch(type) {
        case STAR:
            return Type.TRUE;
        case TRUE:
            return Type.TRUE;
        case FALSE:
            return Type.BOTH;
        case BOTH:
            return Type.BOTH;
        default:
            throw new RuntimeException("Unanticipated node type " + type);
        }
    }

	private static Type addFalse(Type type) {
	    switch(type) {
	    case STAR:
	        return Type.FALSE;
	    case TRUE:
	        return Type.BOTH;
	    case FALSE:
	        return Type.FALSE;
	    case BOTH:
	        return Type.BOTH;
	    default:
	        throw new RuntimeException("Unanticipated node type " + type);
	    }
	}

    /**
	 * Optimizes an already-existing propnet by removing useless leaves.
	 * These are components that have no outputs, but have no special
	 * meaning in GDL that requires them to stay.
	 *
	 * TODO: Currently fails on propnets with cycles.
	 * @param pn
	 */
	public static void lopUselessLeaves(PropNet pn) {
		//Approach: Collect useful propositions based on a backwards
		//search from goal/legal/terminal (passing through transitions)
		Set<Component> usefulComponents = new HashSet<Component>();
		//TODO: Also try with queue?
		Stack<Component> toAdd = new Stack<Component>();
		toAdd.add(pn.getTerminalProposition());
		usefulComponents.add(pn.getInitProposition()); //Can't remove it...
		for(Set<Proposition> goalProps : pn.getGoalPropositions().values())
			toAdd.addAll(goalProps);
		for(Set<Proposition> legalProps : pn.getLegalPropositions().values())
			toAdd.addAll(legalProps);
		while(!toAdd.isEmpty()) {
			Component curComp = toAdd.pop();
			if(usefulComponents.contains(curComp))
				//We've already added it
				continue;
			usefulComponents.add(curComp);
			toAdd.addAll(curComp.getInputs());
		}

		//Remove the components not marked as useful
		List<Component> allComponents = new ArrayList<Component>(pn.getComponents());
		for(Component c : allComponents) {
			if(!usefulComponents.contains(c))
				pn.removeComponent(c);
		}
	}

	/**
	 * Optimizes an already-existing propnet by removing propositions
	 * of the form (init ?x). Does NOT remove the proposition "INIT".
	 * @param pn
	 */
	public static void removeInits(PropNet pn) {
		List<Proposition> toRemove = new ArrayList<Proposition>();
		for(Proposition p : pn.getPropositions()) {
			if(p.getName() instanceof GdlRelation) {
				GdlRelation relation = (GdlRelation) p.getName();
				if(relation.getName() == INIT) {
					toRemove.add(p);
				}
			}
		}

		for(Proposition p : toRemove) {
			pn.removeComponent(p);
		}
	}

	public static void removeRedundantConstantsAndGates(PolymorphicPropNet pn)
	{
	    //	What about constants other than true and false props?
	    Set<PolymorphicComponent> redundantComponents = new HashSet<PolymorphicComponent>();
	    int removedRedundantComponentCount = 0;
	    
	    PolymorphicComponent trueConst = null;
	    PolymorphicComponent falseConst = null;
	    
	    do
	    {
	    	redundantComponents.clear();
	    	
		    for(PolymorphicComponent c : pn.getComponents())
		    {
		    	if ( c instanceof PolymorphicConstant &&
		    		 c != trueConst &&
		    		 c != falseConst )
		    	{
		    		if (trueConst == null && c.getValue())
		    		{
		    			trueConst = c;
		    		}
		    		else if (falseConst == null && !c.getValue())
		    		{
		    			falseConst = c;
		    		}
		    		else
		    		{
		    			redundantComponents.add(c);
		    		}
		    	}
		    	else
		    	{
		    		if ( c instanceof PolymorphicAnd )
		    		{
		    			if ( c.getOutputs().size() == 1 && c.getSingleOutput() instanceof PolymorphicAnd)
	    				{
		    	    		redundantComponents.add(c);
	    				}
		    		}
		    		else if ( c instanceof PolymorphicOr )
		    		{
		    			if ( c.getOutputs().size() == 1 && c.getSingleOutput() instanceof PolymorphicOr)
	    				{
		    	    		redundantComponents.add(c);
	    				}
		    		}
		    		else if ( c instanceof PolymorphicNot )
		    		{
		    			if ( c.getOutputs().size() == 1 && c.getSingleOutput() instanceof PolymorphicNot)
	    				{
		    	    		redundantComponents.add(c);
	    				}
		    		}
		    	}
		    }
		    
		    for(PolymorphicComponent c : redundantComponents)
		    {
		    	if (c instanceof PolymorphicConstant)
		    	{
		    		for(PolymorphicComponent output : c.getOutputs())
		    		{
		 	    		output.removeInput(c);
			 	    	if ( c.getValue() )
				    	{
			 	    		trueConst.addOutput(output);
			 	    		output.addInput(trueConst);
				    	}
				    	else
				    	{
			 	    		falseConst.addOutput(output);
			 	    		output.addInput(falseConst);
				    	}
		    		}
		    	}
		    	else if ( (c instanceof PolymorphicAnd) || (c instanceof PolymorphicOr))
		    	{
		    		PolymorphicComponent output = c.getSingleOutput();
		    		
		    		output.removeInput(c);
		    		
		    		for(PolymorphicComponent input : c.getInputs())
		    		{
		    			input.removeOutput(c);
		    			input.addOutput(output);
		    			output.addInput(input);
		    		}
		    	}
		    	else if ( c instanceof PolymorphicNot)
		    	{
		    		PolymorphicComponent nextInLine = c.getSingleOutput();
		    		PolymorphicComponent input = c.getSingleInput();
		    		
		    		input.removeOutput(c);
		    		
		    		for(PolymorphicComponent output : nextInLine.getOutputs())
		    		{
		    			output.removeInput(nextInLine);
		    			output.addInput(input);
		    			input.addOutput(output);
		    		}
		    		
		    		removedRedundantComponentCount++;
		    		pn.removeComponent(nextInLine);
		    	}
	    		
		    	removedRedundantComponentCount++;
		    	pn.removeComponent(c);
		    }
		} while (redundantComponents.size() > 0);
	    
	    //	Eliminate TRUE inputs to ANDs and FALSE inputs to ORs.  Also eliminate single input ANDs/ORs
    	List<PolymorphicComponent> eliminations = new LinkedList<PolymorphicComponent>();
    	
	    do
	    {
	    	eliminations.clear();
	    	
	    	if ( trueConst != null )
	    	{
	    		List<PolymorphicComponent> disconnected = new LinkedList<PolymorphicComponent>();
	    		
		    	for(PolymorphicComponent c : trueConst.getOutputs())
		    	{
		    		if ( c instanceof PolymorphicAnd )
		    		{
		    			c.removeInput(trueConst);
		    			disconnected.add(c);
		    		}
		    	}
		    	for(PolymorphicComponent c : disconnected)
		    	{
	    			trueConst.removeOutput(c);
		    	}
	    	}
	    	
	    	if ( falseConst != null )
	    	{
	    		List<PolymorphicComponent> disconnected = new LinkedList<PolymorphicComponent>();
	    		
		    	for(PolymorphicComponent c : falseConst.getOutputs())
		    	{
		    		if ( c instanceof PolymorphicOr )
		    		{
		    			c.removeInput(falseConst);
		    			disconnected.add(c);
		    		}
		    		else if ( c instanceof PolymorphicProposition &&
		    				  c.getOutputs().size() == 0 )
		    		{
		    			c.removeInput(falseConst);
		    			disconnected.add(c);
		    		}
		    	}
		    	for(PolymorphicComponent c : disconnected)
		    	{
		    		falseConst.removeOutput(c);
		    	}
	    	}
	    	
	    	
	    	for(PolymorphicComponent c : pn.getComponents())
	    	{
	    		if ( c instanceof PolymorphicAnd || c instanceof PolymorphicOr )
	    		{
	    			if ( c.getInputs().size() == 1 )
	    			{
	    				PolymorphicComponent input = c.getSingleInput();
	    				
	    				for(PolymorphicComponent output : c.getOutputs())
	    				{
	    					output.removeInput(c);
	    					output.addInput(input);
	    					input.addOutput(output);
	    				}
	    				
	    				eliminations.add(c);
	    			}
	    			else if ( c.getOutputs().size() == 0)
	    			{
	    				eliminations.add(c);
	    			}
	    		}
	    		else if ( c.getOutputs().size() == 0 )
	    		{
	    			if ( !isEssentialProposition(c, c.getInputs().size()==0) &&
	    				 (!(c instanceof PolymorphicProposition) || !pn.getBasePropositions().values().contains((PolymorphicProposition)c)))
	    			{
	    				eliminations.add(c);
	    			}
	    		}
	    	}
	    	
	    	for(PolymorphicComponent c : eliminations)
	    	{
	    		for(PolymorphicComponent input : c.getInputs())
	    		{
	    			input.removeOutput(c);
	    		}
	    		pn.removeComponent(c);
	    		removedRedundantComponentCount++;
	    	}
	    } while(eliminations.size() > 0);
	    
	    System.out.println("Removed " + removedRedundantComponentCount + " redundant components");
	}
	
	private static final int largeGateThreshold = 5;
	
	public static void refactorLargeGates(PolymorphicPropNet pn)
	{
		Set<PolymorphicComponent> newComponents = new HashSet<PolymorphicComponent>();
		Set<PolymorphicComponent> removedComponents = new HashSet<PolymorphicComponent>();
		
		for(PolymorphicComponent c : pn.getComponents())
		{
			if ( (c instanceof PolymorphicOr) && c.getInputs().size() > largeGateThreshold )			
			{
				//	Can we find a common factor across input ANDs?
				Set<PolymorphicComponent> inputANDinputs = new HashSet<PolymorphicComponent>();
				boolean nonAndsPresent = false;
				int numFactorInstances = 0;
				int numPotentiallyRemovableComponents = 0;
				
				for(PolymorphicComponent input : c.getInputs())
				{
					if ( !(input instanceof PolymorphicAnd) )
					{
						//	Not unform
						nonAndsPresent = true;
						continue;
					}

					if ( input.getOutputs().size() != 1 )
					{
						//	If the output is used for other purposes too we cannot factor it
						inputANDinputs.clear();
						break;
					}
					
					numFactorInstances++;
					
					if ( inputANDinputs.isEmpty() )
					{
						inputANDinputs.addAll(input.getInputs());
					}
					else
					{
						Collection<? extends PolymorphicComponent> testInputs = input.getInputs();
						
						for(Iterator<PolymorphicComponent> itr = inputANDinputs.iterator(); itr.hasNext(); )
						{						
							if ( !testInputs.contains(itr.next()))
							{
								itr.remove();
							}
						}
						
						if ( inputANDinputs.isEmpty() )
						{
							break;
						}
						
						if ( testInputs.size() <= inputANDinputs.size()+1 )
						{
							numPotentiallyRemovableComponents++;
						}
					}
				}
				
				if ( !inputANDinputs.isEmpty() && numFactorInstances > 1 && numPotentiallyRemovableComponents > 1 )
				{
					PolymorphicComponent factoredOr;
					
					System.out.println("Found factorable OR of size " + c.getInputs().size() + ", with " + inputANDinputs.size() + " factors");
					int removedCount = 0;
					int addedCount = 0;
					
					if ( nonAndsPresent )
					{
						factoredOr = pn.getComponentFactory().createOr(-1, -1);
						newComponents.add(factoredOr);
						addedCount++;
					}
					else
					{
						factoredOr = c;
					}
					
					Set<PolymorphicComponent> removedOrInputs = new HashSet<PolymorphicComponent>();
					Set<PolymorphicComponent> addedOrInputs = new HashSet<PolymorphicComponent>();
					
					//	We have one or more common factors - move them past the OR
					for(PolymorphicComponent input : c.getInputs())
					{
						if ( input instanceof PolymorphicAnd )
						{
							for(PolymorphicComponent factoredInput : inputANDinputs)
							{
								input.removeInput(factoredInput);
								factoredInput.removeOutput(input);
							}
							
							switch ( input.getInputs().size() )
							{
							case 0:
								removedComponents.add(input);
								removedOrInputs.add(input);
								removedCount++;
								break;
							case 1:
								removedComponents.add(input);
								removedOrInputs.add(input);
								removedCount++;
								
								PolymorphicComponent source = input.getSingleInput();
								
								if ( c == factoredOr )
								{
									addedOrInputs.add(source);
								}
								else
								{
									factoredOr.addInput(source);
								}
								source.removeOutput(input);
								source.addOutput(factoredOr);
								break;
							default:
								if ( c != factoredOr )
								{
									removedOrInputs.add(input);
									input.removeOutput(c);
									factoredOr.addInput(input);
									input.addOutput(factoredOr);
								}
								break;
							}
						}
					}

					for(PolymorphicComponent removed : removedOrInputs)
					{
						c.removeInput(removed);
					}

					for(PolymorphicComponent added : addedOrInputs)
					{
						c.addInput(added);
					}

					PolymorphicAnd newAnd = pn.getComponentFactory().createAnd(-1, -1);
					newComponents.add(newAnd);
					addedCount++;
					
					if ( c == factoredOr )
					{
						for(PolymorphicComponent output : c.getOutputs())
						{
							newAnd.addOutput(output);
							output.removeInput(c);
							output.addInput(newAnd);
						}
						
						c.removeAllOutputs();
					}
					else
					{
						newAnd.addOutput(c);
						c.addInput(newAnd);
					}
					
					factoredOr.addOutput(newAnd);
					newAnd.addInput(factoredOr);
					
					for(PolymorphicComponent input : inputANDinputs)
					{
						input.addOutput(newAnd);
						newAnd.addInput(input);
					}
					System.out.println(removedCount + " components removed and " + addedCount + " added for this factorization");
				}
			}
		}
		
		int netRemovedCount = removedComponents.size() - newComponents.size();
		System.out.println("Net components removed by factorization: " + netRemovedCount);
		if ( netRemovedCount < 0 )
		{
			System.out.println("Unexpected growth in size from factorization");
		}
		
		for(PolymorphicComponent c : newComponents)
		{
			pn.addComponent(c);
		}
		
		for(PolymorphicComponent c : removedComponents)
		{
			pn.removeComponent(c);
		}
	}
	
	/**
	 * Potentially optimizes an already-existing propnet by removing propositions
	 * with no special meaning. The inputs and outputs of those propositions
	 * are connected to one another. This is unlikely to improve performance
	 * unless values of every single component are stored (outside the
	 * propnet).
	 *
	 * @param pn
	 */
	public static void removeAnonymousPropositions(PolymorphicPropNet pn) {
		List<PolymorphicProposition> toSplice = new ArrayList<PolymorphicProposition>();
		List<PolymorphicProposition> toReplaceWithFalse = new ArrayList<PolymorphicProposition>();
		for(PolymorphicProposition p : pn.getPropositions()) {
			//If it's important, continue to the next proposition
			if(p.getInputs().size() == 1 && p.getSingleInput() instanceof PolymorphicTransition)
				//It's a base proposition
				continue;
			GdlSentence sentence = p.getName();
			if(sentence instanceof GdlProposition) {
				if(sentence.getName() == TERMINAL || sentence.getName() == INIT_CAPS)
					continue;
			} else {
				GdlRelation relation = (GdlRelation) sentence;
				GdlConstant name = relation.getName();
				if(name == LEGAL || name == GOAL || name == DOES
						|| name == INIT)
					continue;
			}
			if(p.getInputs().size() < 1) {
				//Needs to be handled separately...
				//because this is an always-false true proposition
				//and it might have and gates as outputs
				toReplaceWithFalse.add(p);
				continue;
			}
			if(p.getInputs().size() != 1)
				System.err.println("Might have falsely declared " + p.getName() + " to be unimportant?");
			//Not important
			//System.out.println("Removing " + p);
			toSplice.add(p);
		}
		for(PolymorphicProposition p : toSplice) {
			//Get the inputs and outputs...
			Collection<? extends PolymorphicComponent> inputs = p.getInputs();
			Collection<? extends PolymorphicComponent> outputs = p.getOutputs();
			//Remove the proposition...
			pn.removeComponent(p);
			//And splice the inputs and outputs back together
			if(inputs.size() > 1)
				System.err.println("Programmer made a bad assumption here... might lead to trouble?");
			for(PolymorphicComponent input : inputs) {
				for(PolymorphicComponent output : outputs) {
					input.addOutput(output);
					output.addInput(input);
				}
			}
		}
		for(PolymorphicProposition p : toReplaceWithFalse) {
			System.out.println("Should be replacing " + p + " with false, but should do that in the OPNF, really; better equipped to do that there");
		}
	}
	
	public static void removeInitPropositions(PolymorphicPropNet propNet)
	{
		List<PolymorphicComponent> removedComponents = new LinkedList<PolymorphicComponent>();
		
		for(PolymorphicComponent c : propNet.getComponents())
		{
			if ( c instanceof PolymorphicProposition)
			{
				GdlConstant name = ((PolymorphicProposition)c).getName().getName();
				
				if ( name == INIT )
				{
					if ( c.getInputs().size() > 0 )
					{
						c.getSingleInput().removeOutput(c);
					}
					for(PolymorphicComponent output : c.getOutputs())
					{
						output.removeInput(c);
					}
					
					removedComponents.add(c);
				}
			}
		}
		
		for(PolymorphicComponent c : removedComponents)
		{
			propNet.removeComponent(c);
		}
	}
	
	public static void fixBaseProposition(PolymorphicPropNet propNet, GdlSentence propName, boolean value)
	{
		GdlSentence result = null;
		
		System.out.println("Hardwire base prop " + propName + " to value: " + value);
		PolymorphicProposition prop = propNet.getBasePropositions().get(propName);
		PolymorphicConstant replacement = propNet.getComponentFactory().createConstant(-1, value);
		
		propNet.addComponent(replacement);
		
		for(PolymorphicComponent c : prop.getOutputs())
		{
			c.removeInput(prop);
			c.addInput(replacement);
			replacement.addOutput(c);
		}

		prop.removeAllOutputs();

		int numStartComponents;
		int numEndComponents;
		
		do
		{
			numStartComponents = propNet.getComponents().size();

	        OptimizingPolymorphicPropNetFactory.removeUnreachableBasesAndInputs(propNet, true);
	        OptimizingPolymorphicPropNetFactory.removeRedundantConstantsAndGates(propNet);
	        
			numEndComponents = propNet.getComponents().size();
		} while(numEndComponents != numStartComponents);
	}
}
