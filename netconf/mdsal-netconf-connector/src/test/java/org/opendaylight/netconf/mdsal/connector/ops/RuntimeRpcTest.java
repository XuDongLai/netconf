/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.mdsal.connector.ops;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteSource;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.transform.TransformerException;
import org.custommonkey.xmlunit.DetailedDiff;
import org.custommonkey.xmlunit.Diff;
import org.custommonkey.xmlunit.XMLUnit;
import org.custommonkey.xmlunit.examples.RecursiveElementNameAndTextQualifier;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.opendaylight.controller.config.util.xml.DocumentedException;
import org.opendaylight.controller.config.util.xml.DocumentedException.ErrorSeverity;
import org.opendaylight.controller.config.util.xml.DocumentedException.ErrorTag;
import org.opendaylight.controller.config.util.xml.DocumentedException.ErrorType;
import org.opendaylight.controller.config.util.xml.XmlUtil;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcAvailabilityListener;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcException;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcResult;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcService;
import org.opendaylight.controller.md.sal.dom.spi.DefaultDOMRpcResult;
import org.opendaylight.controller.sal.core.api.model.SchemaService;
import org.opendaylight.netconf.mapping.api.HandlingPriority;
import org.opendaylight.netconf.mapping.api.NetconfOperationChainedExecution;
import org.opendaylight.netconf.mdsal.connector.CurrentSchemaContext;
import org.opendaylight.netconf.util.test.XmlFileLoader;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.api.schema.DataContainerChild;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.impl.schema.builder.impl.ImmutableContainerNodeBuilder;
import org.opendaylight.yangtools.yang.model.api.ContainerSchemaNode;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.RpcDefinition;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaContextListener;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.opendaylight.yangtools.yang.model.repo.api.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.repo.api.YangTextSchemaSource;
import org.opendaylight.yangtools.yang.model.repo.spi.SchemaSourceProvider;
import org.opendaylight.yangtools.yang.parser.spi.meta.ReactorException;
import org.opendaylight.yangtools.yang.parser.stmt.reactor.CrossSourceStatementReactor;
import org.opendaylight.yangtools.yang.parser.stmt.rfc6020.YangInferencePipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

public class RuntimeRpcTest {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeRpcTest.class);

    private String sessionIdForReporting = "netconf-test-session1";

    private static Document RPC_REPLY_OK = null;

    static {
        try {
            RPC_REPLY_OK = XmlFileLoader.xmlFileToDocument("messages/mapping/rpcs/runtimerpc-ok-reply.xml");
        } catch (Exception e) {
            LOG.debug("unable to load rpc reply ok.", e);
            RPC_REPLY_OK = XmlUtil.newDocument();
        }
    }

    private DOMRpcService rpcServiceVoidInvoke = new DOMRpcService() {
        @Nonnull
        @Override
        public CheckedFuture<DOMRpcResult, DOMRpcException> invokeRpc(@Nonnull SchemaPath type, @Nullable NormalizedNode<?, ?> input) {
            return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(null, Collections.<RpcError>emptyList()));
        }

        @Nonnull
        @Override
        public <T extends DOMRpcAvailabilityListener> ListenerRegistration<T> registerRpcListener(@Nonnull T listener) {
            return null;
        }
    };

    private DOMRpcService rpcServiceFailedInvocation = new DOMRpcService() {
        @Nonnull
        @Override
        public CheckedFuture<DOMRpcResult, DOMRpcException> invokeRpc(@Nonnull SchemaPath type, @Nullable NormalizedNode<?, ?> input) {
            return Futures.immediateFailedCheckedFuture((DOMRpcException) new DOMRpcException("rpc invocation not implemented yet") {
            });
        }

        @Nonnull
        @Override
        public <T extends DOMRpcAvailabilityListener> ListenerRegistration<T> registerRpcListener(@Nonnull T listener) {
            return null;
        }
    };

    private DOMRpcService rpcServiceSuccesfullInvocation = new DOMRpcService() {
        @Nonnull
        @Override
        public CheckedFuture<DOMRpcResult, DOMRpcException> invokeRpc(@Nonnull SchemaPath type, @Nullable NormalizedNode<?, ?> input) {
            Collection<DataContainerChild<? extends YangInstanceIdentifier.PathArgument, ?>> children = (Collection) input.getValue();
            Module module = schemaContext.findModuleByNamespaceAndRevision(type.getLastComponent().getNamespace(), null);
            RpcDefinition rpcDefinition = getRpcDefinitionFromModule(module, module.getNamespace(), type.getLastComponent().getLocalName());
            ContainerSchemaNode outputSchemaNode = rpcDefinition.getOutput();
            ContainerNode node = ImmutableContainerNodeBuilder.create()
                    .withNodeIdentifier(new YangInstanceIdentifier.NodeIdentifier(outputSchemaNode.getQName()))
                    .withValue(children).build();

            return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(node));
        }

        @Nonnull
        @Override
        public <T extends DOMRpcAvailabilityListener> ListenerRegistration<T> registerRpcListener(@Nonnull T listener) {
            return null;
        }
    };

    private SchemaContext schemaContext = null;
    private CurrentSchemaContext currentSchemaContext = null;
    @Mock
    private SchemaService schemaService;
    @Mock
    private SchemaContextListener listener;
    @Mock
    private ListenerRegistration registration;
    @Mock
    private SchemaSourceProvider<YangTextSchemaSource> sourceProvider;

    @Before
    public void setUp() throws Exception {

        initMocks(this);
        doNothing().when(registration).close();
        doReturn(listener).when(registration).getInstance();
        doNothing().when(schemaService).addModule(any(Module.class));
        doNothing().when(schemaService).removeModule(any(Module.class));
        doReturn(schemaContext).when(schemaService).getGlobalContext();
        doReturn(schemaContext).when(schemaService).getSessionContext();
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                ((SchemaContextListener) invocationOnMock.getArguments()[0]).onGlobalContextUpdated(schemaContext);
                return registration;
            }
        }).when(schemaService).registerSchemaContextListener(any(SchemaContextListener.class));

        XMLUnit.setIgnoreWhitespace(true);
        XMLUnit.setIgnoreAttributeOrder(true);

        doAnswer(new Answer() {
            @Override
            public Object answer(final InvocationOnMock invocationOnMock) throws Throwable {
                final SourceIdentifier sId = (SourceIdentifier) invocationOnMock.getArguments()[0];
                final YangTextSchemaSource yangTextSchemaSource =
                        YangTextSchemaSource.delegateForByteSource(sId, ByteSource.wrap("module test".getBytes()));
                return Futures.immediateCheckedFuture(yangTextSchemaSource);

            }
        }).when(sourceProvider).getSource(any(SourceIdentifier.class));

        this.schemaContext = parseYangStreams(getYangSchemas());
        this.currentSchemaContext = new CurrentSchemaContext(schemaService, sourceProvider);
    }

    @Test
    public void testVoidOutputRpc() throws Exception {
        RuntimeRpc rpc = new RuntimeRpc(sessionIdForReporting, currentSchemaContext, rpcServiceVoidInvoke);

        Document rpcDocument = XmlFileLoader.xmlFileToDocument("messages/mapping/rpcs/rpc-void-output.xml");
        HandlingPriority priority = rpc.canHandle(rpcDocument);
        Preconditions.checkState(priority != HandlingPriority.CANNOT_HANDLE);

        Document response = rpc.handle(rpcDocument, NetconfOperationChainedExecution.EXECUTION_TERMINATION_POINT);

        verifyResponse(response, RPC_REPLY_OK);
    }

    @Test
    public void testSuccesfullNonVoidInvocation() throws Exception {
        RuntimeRpc rpc = new RuntimeRpc(sessionIdForReporting, currentSchemaContext, rpcServiceSuccesfullInvocation);

        Document rpcDocument = XmlFileLoader.xmlFileToDocument("messages/mapping/rpcs/rpc-nonvoid.xml");
        HandlingPriority priority = rpc.canHandle(rpcDocument);
        Preconditions.checkState(priority != HandlingPriority.CANNOT_HANDLE);

        Document response = rpc.handle(rpcDocument, NetconfOperationChainedExecution.EXECUTION_TERMINATION_POINT);
        verifyResponse(response, XmlFileLoader.xmlFileToDocument("messages/mapping/rpcs/rpc-nonvoid-control.xml"));
    }

    @Test
    public void testSuccesfullContainerInvocation() throws Exception {
        RuntimeRpc rpc = new RuntimeRpc(sessionIdForReporting, currentSchemaContext, rpcServiceSuccesfullInvocation);

        Document rpcDocument = XmlFileLoader.xmlFileToDocument("messages/mapping/rpcs/rpc-container.xml");
        HandlingPriority priority = rpc.canHandle(rpcDocument);
        Preconditions.checkState(priority != HandlingPriority.CANNOT_HANDLE);

        Document response = rpc.handle(rpcDocument, NetconfOperationChainedExecution.EXECUTION_TERMINATION_POINT);
        verifyResponse(response, XmlFileLoader.xmlFileToDocument("messages/mapping/rpcs/rpc-container-control.xml"));
    }

    @Test
    public void testFailedInvocation() throws Exception {
        RuntimeRpc rpc = new RuntimeRpc(sessionIdForReporting, currentSchemaContext, rpcServiceFailedInvocation);

        Document rpcDocument = XmlFileLoader.xmlFileToDocument("messages/mapping/rpcs/rpc-nonvoid.xml");
        HandlingPriority priority = rpc.canHandle(rpcDocument);
        Preconditions.checkState(priority != HandlingPriority.CANNOT_HANDLE);

        try {
            rpc.handle(rpcDocument, NetconfOperationChainedExecution.EXECUTION_TERMINATION_POINT);
            fail("should have failed with rpc invocation not implemented yet");
        } catch (DocumentedException e) {
            assertTrue(e.getErrorType() == ErrorType.application);
            assertTrue(e.getErrorSeverity() == ErrorSeverity.error);
            assertTrue(e.getErrorTag() == ErrorTag.operation_failed);
        }
    }

    @Test
    public void testVoidInputOutputRpc() throws Exception {
        RuntimeRpc rpc = new RuntimeRpc(sessionIdForReporting, currentSchemaContext, rpcServiceVoidInvoke);

        Document rpcDocument = XmlFileLoader.xmlFileToDocument("messages/mapping/rpcs/rpc-void-input-output.xml");
        HandlingPriority priority = rpc.canHandle(rpcDocument);
        Preconditions.checkState(priority != HandlingPriority.CANNOT_HANDLE);

        Document response = rpc.handle(rpcDocument, NetconfOperationChainedExecution.EXECUTION_TERMINATION_POINT);

        verifyResponse(response, RPC_REPLY_OK);
    }

    @Test
    public void testBadNamespaceInRpc() throws Exception {
        RuntimeRpc rpc = new RuntimeRpc(sessionIdForReporting, currentSchemaContext, rpcServiceVoidInvoke);
        Document rpcDocument = XmlFileLoader.xmlFileToDocument("messages/mapping/rpcs/rpc-bad-namespace.xml");

        try {
            rpc.handle(rpcDocument, NetconfOperationChainedExecution.EXECUTION_TERMINATION_POINT);
            fail("Should have failed, rpc has bad namespace");
        } catch (DocumentedException e) {
            assertTrue(e.getErrorSeverity() == ErrorSeverity.error);
            assertTrue(e.getErrorTag() == ErrorTag.bad_element);
            assertTrue(e.getErrorType() == ErrorType.application);
        }
    }

    private void verifyResponse(Document response, Document template) throws IOException, TransformerException {
        DetailedDiff dd = new DetailedDiff(new Diff(response, template));
        dd.overrideElementQualifier(new RecursiveElementNameAndTextQualifier());
        //we care about order so response has to be identical
        assertTrue(dd.identical());
    }

    private RpcDefinition getRpcDefinitionFromModule(Module module, URI namespaceURI, String name) {
        for (RpcDefinition rpcDef : module.getRpcs()) {
            if (rpcDef.getQName().getNamespace().equals(namespaceURI)
                    && rpcDef.getQName().getLocalName().equals(name)) {
                return rpcDef;
            }
        }

        return null;

    }

    private List<InputStream> getYangSchemas() {
        final List<String> schemaPaths = Arrays.asList("/yang/mdsal-netconf-rpc-test.yang");
        final List<InputStream> schemas = new ArrayList<>();

        for (String schemaPath : schemaPaths) {
            InputStream resourceAsStream = getClass().getResourceAsStream(schemaPath);
            schemas.add(resourceAsStream);
        }

        return schemas;
    }

    private static SchemaContext parseYangStreams(final List<InputStream> streams) {
        CrossSourceStatementReactor.BuildAction reactor = YangInferencePipeline.RFC6020_REACTOR
                .newBuild();
        final SchemaContext schemaContext;
        try {
            schemaContext = reactor.buildEffective(streams);
        } catch (ReactorException e) {
            throw new RuntimeException("Unable to build schema context from " + streams, e);
        }
        return schemaContext;
    }
}