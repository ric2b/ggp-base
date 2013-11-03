package org.ggp.base.util.propnet.polymorphic;

import java.util.List;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.statemachine.Role;

public abstract class PolymorphicComponentFactory {
	public abstract PolymorphicPropNet createPropNet(PropNet basicPropNet);
	public abstract PolymorphicPropNet createPropNet(PolymorphicPropNet propNet);
	public abstract PolymorphicPropNet createPropNet(List<Role> roles, Set<PolymorphicComponent> components);
	public abstract PolymorphicAnd createAnd(int numInputs, int numOutputs);
	public abstract PolymorphicOr createOr(int numInputs, int numOutputs);
	public abstract PolymorphicNot createNot(int numOutputs);
	public abstract PolymorphicConstant createConstant(int numOutputs, boolean value);
	public abstract PolymorphicProposition createProposition(int numOutputs, GdlSentence name);
	public abstract PolymorphicTransition createTransition(int numOutputs);
}
