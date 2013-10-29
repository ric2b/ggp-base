package org.ggp.base.util.propnet.polymorphic.learning;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponentFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

public class LearningComponentFactory extends PolymorphicComponentFactory {

	public LearningComponentFactory()
	{
	}
	
	@Override
	public PolymorphicAnd createAnd(int numInputs, int numOutputs) {
		return new LearningAnd();
	}
	
	@Override
	public PolymorphicOr createOr(int numInputs, int numOutputs) {
		return new LearningOr();
	}
	
	@Override
	public PolymorphicNot createNot(int numOutputs) {
		return new LearningNot();
	}

	@Override
	public PolymorphicConstant createConstant(int numOutputs, boolean value)
	{
		return new LearningConstant(value);
	}
	
	@Override
	public PolymorphicProposition createProposition(int numOutputs, GdlSentence name)
	{
		return new LearningProposition(name);
	}

	@Override
	public PolymorphicTransition createTransition(int numOutputs)
	{
		return new LearningTransition();
	}
}
