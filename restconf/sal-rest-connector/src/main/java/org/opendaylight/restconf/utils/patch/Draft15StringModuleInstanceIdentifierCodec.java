/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.restconf.utils.patch;

import com.google.common.base.Preconditions;
import java.net.URI;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.opendaylight.yangtools.yang.data.util.AbstractModuleStringInstanceIdentifierCodec;
import org.opendaylight.yangtools.yang.data.util.DataSchemaContextTree;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;

final class Draft15StringModuleInstanceIdentifierCodec extends AbstractModuleStringInstanceIdentifierCodec {

    private final DataSchemaContextTree dataContextTree;
    private final SchemaContext context;
    private final String defaultPrefix;

    Draft15StringModuleInstanceIdentifierCodec(SchemaContext context) {
        this.context = Preconditions.checkNotNull(context);
        this.dataContextTree = DataSchemaContextTree.from(context);
        this.defaultPrefix = "";
    }

    Draft15StringModuleInstanceIdentifierCodec(SchemaContext context, @Nonnull String defaultPrefix) {
        this.context = Preconditions.checkNotNull(context);
        this.dataContextTree = DataSchemaContextTree.from(context);
        this.defaultPrefix = defaultPrefix;
    }

    @Override
    protected Module moduleForPrefix(@Nonnull String prefix) {
        if (prefix.isEmpty() && !this.defaultPrefix.isEmpty()) {
            return this.context.findModuleByName(this.defaultPrefix, null);
        } else {
            return this.context.findModuleByName(prefix, null);
        }
    }

    @Nonnull
    @Override
    protected DataSchemaContextTree getDataContextTree() {
        return this.dataContextTree;
    }

    @Nullable
    @Override
    protected String prefixForNamespace(@Nonnull URI namespace) {
        final Module module = this.context.findModuleByNamespaceAndRevision(namespace, null);
        return module == null ? null : module.getName();
    }
}