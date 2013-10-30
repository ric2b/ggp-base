package org.ggp.base.util.propnet.polymorphic;

import java.util.Collection;

/**
 * The root class of the PolymorphicComponent hierarchy, which is designed to represent
 * nodes in a PropNet. The general contract of derived classes is to override
 * all methods.
 * This mirrors the class Component in the basic propnet code
 */

public abstract interface PolymorphicComponent
{
    /** The inputs to the component. */

    /**
     * Adds a new input.
     * 
     * @param input
     *            A new input.
     */
    public abstract void addInput(PolymorphicComponent input);

    /**
     * Adds a new output.
     * 
     * @param output
     *            A new output.
     */
    public abstract void addOutput(PolymorphicComponent output);

    /**
     * Getter method.
     * 
     * @return The inputs to the component.
     */
    public abstract Collection<? extends PolymorphicComponent> getInputs();
    
    /**
     * A convenience method, to get a single input.
     * To be used only when the component is known to have
     * exactly one input.
     * 
     * @return The single input to the component.
     */
    public abstract PolymorphicComponent getSingleInput();
    
    /**
     * Getter method.
     * 
     * @return The outputs of the component.
     */
    public abstract Collection<? extends PolymorphicComponent> getOutputs();
    
    /**
     * A convenience method, to get a single output.
     * To be used only when the component is known to have
     * exactly one output.
     * 
     * @return The single output to the component.
     */
    public abstract PolymorphicComponent getSingleOutput();

    /**
     * Gets the value of the Component.
     * 
     * @return The value of the Component.
     */
    public abstract boolean getValue();
 
    /**
     * Returns a representation of the Component in .dot format.
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public abstract String toString();

}