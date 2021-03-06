
package org.ggp.base.util.propnet.polymorphic;


/**
 * @author steve
 * Base class for all non-abstract Transition implementations. Needed so that
 * instanceof PolymorphicTransition can be used regardless of the concrete class
 * hierarchy produced by the factory
 */
public abstract interface PolymorphicTransition extends PolymorphicComponent
{
  //  No actual differences from the base class
}
