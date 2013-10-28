package org.ggp.base.util.propnet.polymorphic;

import org.ggp.base.util.propnet.architecture.Component;

//Base class for all non-abstract And implementations.  Need so that
//instanceof PolymorphicOr can be used regardless of the concrete class hierarchy produced by the factory
public abstract interface PolymorphicOr extends PolymorphicComponent {

}
