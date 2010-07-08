/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.construct;

import java.beans.ExceptionListener;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mule.api.MuleContext;
import org.mule.api.MuleException;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.construct.FlowConstructAware;
import org.mule.api.construct.FlowConstructInvalidException;
import org.mule.api.context.MuleContextAware;
import org.mule.api.lifecycle.Disposable;
import org.mule.api.lifecycle.Initialisable;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.lifecycle.Lifecycle;
import org.mule.api.lifecycle.LifecycleCallback;
import org.mule.api.lifecycle.LifecycleState;
import org.mule.api.lifecycle.LifecycleStateEnabled;
import org.mule.api.lifecycle.Startable;
import org.mule.api.lifecycle.Stoppable;
import org.mule.api.processor.MessageProcessor;
import org.mule.api.processor.MessageProcessorBuilder;
import org.mule.api.source.MessageSource;
import org.mule.processor.builder.ChainMessageProcessorBuilder;
import org.mule.util.ClassUtils;

/**
 * Abstract implementation of {@link FlowConstruct} that: <li>Is constructed with
 * unique name and {@link MuleContext}. <li>Uses a {@link MessageSource} as the
 * source of messages. <li>Uses a chain of {@link MessageProcessor}s to process
 * messages. <li>Has lifecycle and propagates this lifecycle to both
 * {@link MessageSource} and {@link MessageProcessor}s in the correct order depending
 * on the lifecycle phase. <li>Allows an {@link ExceptionListener} to be set. <br/>
 * Implementations of <code>AbstractFlowConstuct</code> should implement
 * {@link#configureMessageProcessors(ChainMessageProcessorBuilder)} and
 * {@link #validateConstruct()} to construct the processing chain required and
 * validate the resulting construct. Validation may include validation of the type of
 * attributes of the {@link MessageSource}.
 * <p/>
 * Implementations may also implement {@link #doInitialise()}, {@link #doStart()},
 * {@link #doStop()} and {@link #doDispose()} if they need to perform any action on
 * lifecycle transitions.
 */
public abstract class AbstractFlowConstruct implements FlowConstruct, Lifecycle, LifecycleStateEnabled
{
    protected transient Log logger = LogFactory.getLog(getClass());

    protected String name;
    protected MessageSource messageSource;
    protected MessageProcessor messageProcessorChain;
    protected ExceptionListener exceptionListener;

    protected final FlowConstructLifecycleManager lifecycleManager;

    protected final MuleContext muleContext;

    public AbstractFlowConstruct(String name, MuleContext muleContext)
    {
        this.muleContext = muleContext;
        this.name = name;
        this.lifecycleManager = new FlowConstructLifecycleManager(this);
    }

    public final void initialise() throws InitialisationException
    {
        try
        {
            lifecycleManager.fireInitialisePhase(new LifecycleCallback<FlowConstruct>()
            {
                public void onTransition(String phaseName, FlowConstruct object) throws MuleException
                {
                    createMessageProcessor();

                    if (messageSource != null)
                    {
                        messageSource.setListener(messageProcessorChain);
                    }

                    injectFlowConstructMuleContext(messageSource);
                    injectFlowConstructMuleContext(messageProcessorChain);
                    initialiseIfInitialisable(messageSource);
                    initialiseIfInitialisable(messageProcessorChain);
                    doInitialise();
                    validateConstruct();
                }
            });

        }
        catch (InitialisationException e)
        {
            throw e;
        }
        catch (MuleException e)
        {
            throw new InitialisationException(e, this);
        }

    }

    public final void start() throws MuleException
    {
        lifecycleManager.fireStartPhase(new LifecycleCallback<FlowConstruct>()
        {
            public void onTransition(String phaseName, FlowConstruct object) throws MuleException
            {
                startIfStartable(messageProcessorChain);
                startIfStartable(messageSource);
                doStart();
            }
        });
    }

    public final void stop() throws MuleException
    {

        lifecycleManager.fireStopPhase(new LifecycleCallback<FlowConstruct>()
        {
            public void onTransition(String phaseName, FlowConstruct object) throws MuleException
            {
                stopIfStoppable(messageSource);
                stopIfStoppable(messageProcessorChain);
                doStop();
            }
        });
    }

    public final void dispose()
    {
        try
        {
            if (isStarted())
            {
                stop();
            }

            lifecycleManager.fireDisposePhase(new LifecycleCallback<FlowConstruct>()
            {
                public void onTransition(String phaseName, FlowConstruct object) throws MuleException
                {
                    disposeIfDisposable(messageProcessorChain);
                    disposeIfDisposable(messageSource);
                    doDispose();
                }
            });
        }
        catch (MuleException e)
        {
            logger.error("Failed to stop service: " + name, e);
        }
    }

    public boolean isStarted()
    {
        return lifecycleManager.getState().isStarted();
    }

    public boolean isStopped()
    {
        return lifecycleManager.getState().isStopped();
    }

    public boolean isStopping()
    {
        return lifecycleManager.getState().isStopping();
    }

    /**
     * Creates a {@link MessageProcessor} that will process messages from the
     * configured {@link MessageSource}.
     * <p>
     * The default implementation of this methods uses a
     * {@link ChainMessageProcessorBuilder} and allows a chain of
     * {@link MessageProcessor}s to be configured using the
     * {@link #configureMessageProcessors(ChainMessageProcessorBuilder)} method but
     * if you wish to use another {@link MessageProcessorBuilder} or just a single
     * {@link MessageProcessor} then this method can be overridden and return a
     * single {@link MessageProcessor} instead.
     */
    protected void createMessageProcessor()
    {
        ChainMessageProcessorBuilder builder = new ChainMessageProcessorBuilder();
        configureMessageProcessors(builder);
        messageProcessorChain = builder.build();
    }

    /**
     * Used to configure the processing chain for this <code>FlowConstuct</code by
     * adding {@link MessageProcessor}s to the chain using the builder provided.
     * <p>
     * To use a different builder of to construct a composite
     * {@link MessageProcessor} manually override {@link #createMessageProcessor()}
     * instead.
     * 
     * @param builder instance of {@link ChainMessageProcessorBuilder}
     */
    protected abstract void configureMessageProcessors(ChainMessageProcessorBuilder builder);

    public String getName()
    {
        return name;
    }

    public void setMessageSource(MessageSource messageSource)
    {
        this.messageSource = messageSource;
    }

    public ExceptionListener getExceptionListener()
    {
        return exceptionListener;
    }

    public void setExceptionListener(ExceptionListener exceptionListener)
    {
        this.exceptionListener = exceptionListener;
    }

    public LifecycleState getLifecycleState()
    {
        return lifecycleManager.getState();
    }

    public MuleContext getMuleContext()
    {
        return muleContext;
    }

    protected void doInitialise() throws InitialisationException
    {
        // Empty template method
    }

    protected void doStart() throws MuleException
    {
        // Empty template method
    }

    protected void doStop() throws MuleException
    {
        // Empty template method
    }

    protected void doDispose()
    {
        // Empty template method
    }

    /**
     * Validates configured flow construct
     * 
     * @throws FlowConstructInvalidException if the flow construct does not pass
     *             validation
     */
    protected void validateConstruct() throws FlowConstructInvalidException
    {
        // Empty template method
    }

    private void injectFlowConstructMuleContext(Object candidate)
    {
        if (candidate instanceof FlowConstructAware)
        {
            ((FlowConstructAware) candidate).setFlowConstruct(this);
        }
        if (candidate instanceof MuleContextAware)
        {
            ((MuleContextAware) candidate).setMuleContext(muleContext);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s{%s}", ClassUtils.getSimpleName(this.getClass()), getName());
    }

    private void initialiseIfInitialisable(Object candidate) throws InitialisationException
    {
        if (candidate instanceof Initialisable)
        {
            ((Initialisable) candidate).initialise();
        }
    }

    private void startIfStartable(Object candidate) throws MuleException
    {
        if (candidate instanceof Startable)
        {
            ((Startable) candidate).start();
        }
    }

    private void stopIfStoppable(Object candidate) throws MuleException
    {
        if (candidate instanceof Stoppable)
        {
            ((Stoppable) candidate).stop();
        }
    }

    private void disposeIfDisposable(Object candidate)
    {
        if (candidate instanceof Disposable)
        {
            ((Disposable) candidate).dispose();
        }
    }

}
