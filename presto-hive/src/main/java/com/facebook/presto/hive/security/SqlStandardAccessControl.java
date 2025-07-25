/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive.security;

import com.facebook.presto.common.Subfield;
import com.facebook.presto.hive.HiveColumnConverterProvider;
import com.facebook.presto.hive.HiveConnectorId;
import com.facebook.presto.hive.HiveTransactionManager;
import com.facebook.presto.hive.TransactionalMetadata;
import com.facebook.presto.hive.metastore.Database;
import com.facebook.presto.hive.metastore.MetastoreContext;
import com.facebook.presto.hive.metastore.SemiTransactionalHiveMetastore;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.connector.ConnectorAccessControl;
import com.facebook.presto.spi.connector.ConnectorTransactionHandle;
import com.facebook.presto.spi.security.AccessControlContext;
import com.facebook.presto.spi.security.AccessDeniedException;
import com.facebook.presto.spi.security.ConnectorIdentity;
import com.facebook.presto.spi.security.PrestoPrincipal;
import com.facebook.presto.spi.security.Privilege;
import com.facebook.presto.spi.security.RoleGrant;
import com.facebook.presto.spi.security.ViewExpression;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.hive.metastore.Database.DEFAULT_DATABASE_NAME;
import static com.facebook.presto.hive.metastore.HivePrivilegeInfo.HivePrivilege;
import static com.facebook.presto.hive.metastore.HivePrivilegeInfo.HivePrivilege.DELETE;
import static com.facebook.presto.hive.metastore.HivePrivilegeInfo.HivePrivilege.INSERT;
import static com.facebook.presto.hive.metastore.HivePrivilegeInfo.HivePrivilege.OWNERSHIP;
import static com.facebook.presto.hive.metastore.HivePrivilegeInfo.HivePrivilege.SELECT;
import static com.facebook.presto.hive.metastore.HivePrivilegeInfo.HivePrivilege.UPDATE;
import static com.facebook.presto.hive.metastore.HivePrivilegeInfo.toHivePrivilege;
import static com.facebook.presto.hive.metastore.thrift.ThriftMetastoreUtil.isRoleApplicable;
import static com.facebook.presto.hive.metastore.thrift.ThriftMetastoreUtil.isRoleEnabled;
import static com.facebook.presto.hive.metastore.thrift.ThriftMetastoreUtil.listApplicableRoles;
import static com.facebook.presto.hive.metastore.thrift.ThriftMetastoreUtil.listApplicableTablePrivileges;
import static com.facebook.presto.hive.metastore.thrift.ThriftMetastoreUtil.listEnabledTablePrivileges;
import static com.facebook.presto.spi.security.AccessDeniedException.denyAddColumn;
import static com.facebook.presto.spi.security.AccessDeniedException.denyAddConstraint;
import static com.facebook.presto.spi.security.AccessDeniedException.denyCreateRole;
import static com.facebook.presto.spi.security.AccessDeniedException.denyCreateSchema;
import static com.facebook.presto.spi.security.AccessDeniedException.denyCreateTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyCreateView;
import static com.facebook.presto.spi.security.AccessDeniedException.denyCreateViewWithSelect;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDeleteTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropColumn;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropConstraint;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropRole;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropSchema;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyDropView;
import static com.facebook.presto.spi.security.AccessDeniedException.denyGrantRoles;
import static com.facebook.presto.spi.security.AccessDeniedException.denyGrantTablePrivilege;
import static com.facebook.presto.spi.security.AccessDeniedException.denyInsertTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyRenameColumn;
import static com.facebook.presto.spi.security.AccessDeniedException.denyRenameSchema;
import static com.facebook.presto.spi.security.AccessDeniedException.denyRenameTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyRenameView;
import static com.facebook.presto.spi.security.AccessDeniedException.denyRevokeRoles;
import static com.facebook.presto.spi.security.AccessDeniedException.denyRevokeTablePrivilege;
import static com.facebook.presto.spi.security.AccessDeniedException.denySelectTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denySetCatalogSessionProperty;
import static com.facebook.presto.spi.security.AccessDeniedException.denySetRole;
import static com.facebook.presto.spi.security.AccessDeniedException.denySetTableProperties;
import static com.facebook.presto.spi.security.AccessDeniedException.denyShowColumnsMetadata;
import static com.facebook.presto.spi.security.AccessDeniedException.denyShowCreateTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyShowRoles;
import static com.facebook.presto.spi.security.AccessDeniedException.denyTruncateTable;
import static com.facebook.presto.spi.security.AccessDeniedException.denyUpdateTableColumns;
import static com.facebook.presto.spi.security.PrincipalType.ROLE;
import static com.facebook.presto.spi.security.PrincipalType.USER;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

public class SqlStandardAccessControl
        implements ConnectorAccessControl
{
    public static final String ADMIN_ROLE_NAME = "admin";
    private static final String INFORMATION_SCHEMA_NAME = "information_schema";
    private static final SchemaTableName ROLES = new SchemaTableName(INFORMATION_SCHEMA_NAME, "roles");

    private final String connectorId;
    private final HiveTransactionManager hiveTransactionManager;

    @Inject
    public SqlStandardAccessControl(
            HiveConnectorId connectorId,
            HiveTransactionManager hiveTransactionManager)
    {
        this.connectorId = requireNonNull(connectorId, "connectorId is null").toString();
        this.hiveTransactionManager = requireNonNull(hiveTransactionManager, "hiveTransactionManager is null");
    }

    @Override
    public void checkCanCreateSchema(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, String schemaName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isAdmin(transaction, identity, metastoreContext)) {
            denyCreateSchema(schemaName);
        }
    }

    @Override
    public void checkCanDropSchema(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, String schemaName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isDatabaseOwner(transaction, identity, metastoreContext, schemaName)) {
            denyDropSchema(schemaName);
        }
    }

    @Override
    public void checkCanRenameSchema(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, String schemaName, String newSchemaName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isDatabaseOwner(transaction, identity, metastoreContext, schemaName)) {
            denyRenameSchema(schemaName, newSchemaName);
        }
    }

    @Override
    public void checkCanShowSchemas(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context)
    {
    }

    @Override
    public Set<String> filterSchemas(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, Set<String> schemaNames)
    {
        return schemaNames;
    }

    @Override
    public void checkCanShowCreateTable(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);

        if (!checkTablePermission(transactionHandle, identity, metastoreContext, tableName, SELECT, true)) {
            denyShowCreateTable(tableName.toString());
        }
    }

    @Override
    public void checkCanCreateTable(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isDatabaseOwner(transaction, identity, metastoreContext, tableName.getSchemaName())) {
            denyCreateTable(tableName.toString());
        }
    }

    @Override
    public void checkCanSetTableProperties(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName, Map<String, Object> properties)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isTableOwner(transactionHandle, identity, metastoreContext, tableName)) {
            denySetTableProperties(tableName.toString());
        }
    }

    @Override
    public void checkCanDropTable(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isTableOwner(transaction, identity, metastoreContext, tableName)) {
            denyDropTable(tableName.toString());
        }
    }

    @Override
    public void checkCanRenameTable(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName, SchemaTableName newTableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isTableOwner(transaction, identity, metastoreContext, tableName)) {
            denyRenameTable(tableName.toString(), newTableName.toString());
        }
    }

    @Override
    public void checkCanShowTablesMetadata(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, String schemaName)
    {
    }

    @Override
    public Set<SchemaTableName> filterTables(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, Set<SchemaTableName> tableNames)
    {
        return tableNames;
    }

    @Override
    public void checkCanShowColumnsMetadata(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);

        if (!hasAnyTablePermission(transactionHandle, identity, metastoreContext, tableName)) {
            denyShowColumnsMetadata(tableName.toString());
        }
    }

    @Override
    public List<ColumnMetadata> filterColumns(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName, List<ColumnMetadata> columns)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);

        if (!hasAnyTablePermission(transactionHandle, identity, metastoreContext, tableName)) {
            return ImmutableList.of();
        }
        return columns;
    }

    @Override
    public void checkCanAddColumn(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isTableOwner(transaction, identity, metastoreContext, tableName)) {
            denyAddColumn(tableName.toString());
        }
    }

    @Override
    public void checkCanDropColumn(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isTableOwner(transaction, identity, metastoreContext, tableName)) {
            denyDropColumn(tableName.toString());
        }
    }

    @Override
    public void checkCanDropConstraint(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isTableOwner(transaction, identity, metastoreContext, tableName)) {
            denyDropConstraint(tableName.toString());
        }
    }

    @Override
    public void checkCanAddConstraint(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isTableOwner(transaction, identity, metastoreContext, tableName)) {
            denyAddConstraint(tableName.toString());
        }
    }

    @Override
    public void checkCanRenameColumn(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isTableOwner(transaction, identity, metastoreContext, tableName)) {
            denyRenameColumn(tableName.toString());
        }
    }

    @Override
    public void checkCanSelectFromColumns(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName, Set<Subfield> columnOrSubfieldNames)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!checkTablePermission(transaction, identity, metastoreContext, tableName, SELECT, false)) {
            denySelectTable(tableName.toString());
        }
    }

    @Override
    public void checkCanInsertIntoTable(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!checkTablePermission(transaction, identity, metastoreContext, tableName, INSERT, false)) {
            denyInsertTable(tableName.toString());
        }
    }

    @Override
    public void checkCanDeleteFromTable(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!checkTablePermission(transaction, identity, metastoreContext, tableName, DELETE, false)) {
            denyDeleteTable(tableName.toString());
        }
    }

    @Override
    public void checkCanTruncateTable(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!checkTablePermission(transaction, identity, metastoreContext, tableName, DELETE, false)) {
            denyTruncateTable(tableName.toString());
        }
    }

    @Override
    public void checkCanUpdateTableColumns(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName, Set<String> updatedColumns)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!checkTablePermission(transaction, identity, metastoreContext, tableName, UPDATE, false)) {
            denyUpdateTableColumns(tableName.toString(), updatedColumns);
        }
    }

    @Override
    public void checkCanCreateView(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName viewName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isDatabaseOwner(transaction, identity, metastoreContext, viewName.getSchemaName())) {
            denyCreateView(viewName.toString());
        }
    }

    @Override
    public void checkCanRenameView(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName viewName, SchemaTableName newViewName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isTableOwner(transaction, identity, metastoreContext, viewName)) {
            denyRenameView(viewName.toString(), newViewName.toString());
        }
    }

    @Override
    public void checkCanDropView(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName viewName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isTableOwner(transaction, identity, metastoreContext, viewName)) {
            denyDropView(viewName.toString());
        }
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName, Set<String> columnNames)
    {
        checkCanSelectFromColumns(transaction, identity, context, tableName, columnNames.stream().map(column -> new Subfield(column)).collect(toImmutableSet()));
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!checkTablePermission(transaction, identity, metastoreContext, tableName, SELECT, true)) {
            denyCreateViewWithSelect(tableName.toString(), identity);
        }
    }

    @Override
    public void checkCanSetCatalogSessionProperty(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, String propertyName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isAdmin(transaction, identity, metastoreContext)) {
            denySetCatalogSessionProperty(connectorId, propertyName);
        }
    }

    @Override
    public void checkCanGrantTablePrivilege(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, Privilege privilege, SchemaTableName tableName, PrestoPrincipal grantee, boolean withGrantOption)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (isTableOwner(transaction, identity, metastoreContext, tableName)) {
            return;
        }

        if (!hasGrantOptionForPrivilege(transaction, identity, metastoreContext, privilege, tableName)) {
            denyGrantTablePrivilege(privilege.name(), tableName.toString());
        }
    }

    @Override
    public void checkCanRevokeTablePrivilege(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, Privilege privilege, SchemaTableName tableName, PrestoPrincipal revokee, boolean grantOptionFor)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (isTableOwner(transaction, identity, metastoreContext, tableName)) {
            return;
        }

        if (!hasGrantOptionForPrivilege(transaction, identity, metastoreContext, privilege, tableName)) {
            denyRevokeTablePrivilege(privilege.name(), tableName.toString());
        }
    }

    @Override
    public void checkCanCreateRole(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, String role, Optional<PrestoPrincipal> grantor)
    {
        // currently specifying grantor is supported by metastore, but it is not supported by Hive itself
        if (grantor.isPresent()) {
            throw new AccessDeniedException("Hive Connector does not support WITH ADMIN statement");
        }
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isAdmin(transactionHandle, identity, metastoreContext)) {
            denyCreateRole(role);
        }
    }

    @Override
    public void checkCanDropRole(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, String role)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isAdmin(transactionHandle, identity, metastoreContext)) {
            denyDropRole(role);
        }
    }

    @Override
    public void checkCanGrantRoles(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, Set<String> roles, Set<PrestoPrincipal> grantees, boolean withAdminOption, Optional<PrestoPrincipal> grantor, String catalogName)
    {
        // currently specifying grantor is supported by metastore, but it is not supported by Hive itself
        if (grantor.isPresent()) {
            throw new AccessDeniedException("Hive Connector does not support GRANTED BY statement");
        }
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!hasAdminOptionForRoles(transactionHandle, identity, metastoreContext, roles)) {
            denyGrantRoles(roles, grantees);
        }
    }

    @Override
    public void checkCanRevokeRoles(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, Set<String> roles, Set<PrestoPrincipal> grantees, boolean adminOptionFor, Optional<PrestoPrincipal> grantor, String catalogName)
    {
        // currently specifying grantor is supported by metastore, but it is not supported by Hive itself
        if (grantor.isPresent()) {
            throw new AccessDeniedException("Hive Connector does not support GRANTED BY statement");
        }
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!hasAdminOptionForRoles(transactionHandle, identity, metastoreContext, roles)) {
            denyRevokeRoles(roles, grantees);
        }
    }

    @Override
    public void checkCanSetRole(ConnectorTransactionHandle transaction, ConnectorIdentity identity, AccessControlContext context, String role, String catalogName)
    {
        Optional<SemiTransactionalHiveMetastore> metastoreOptional = getMetastore(transaction);
        metastoreOptional.ifPresent(metastore -> {
            MetastoreContext metastoreContext = createMetastoreContext(identity, context);
            if (!isRoleApplicable(metastore, identity, new PrestoPrincipal(USER, identity.getUser()), metastoreContext, role)) {
                denySetRole(role);
            }
        });
    }

    @Override
    public void checkCanShowRoles(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, String catalogName)
    {
        MetastoreContext metastoreContext = createMetastoreContext(identity, context);
        if (!isAdmin(transactionHandle, identity, metastoreContext)) {
            denyShowRoles(catalogName);
        }
    }

    @Override
    public void checkCanShowCurrentRoles(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, String catalogName)
    {
    }

    @Override
    public void checkCanShowRoleGrants(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, String catalogName)
    {
    }

    @Override
    public List<ViewExpression> getRowFilters(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName)
    {
        return ImmutableList.of();
    }

    @Override
    public Map<ColumnMetadata, ViewExpression> getColumnMasks(ConnectorTransactionHandle transactionHandle, ConnectorIdentity identity, AccessControlContext context, SchemaTableName tableName, List<ColumnMetadata> columns)
    {
        return ImmutableMap.of();
    }

    private static MetastoreContext createMetastoreContext(ConnectorIdentity identity, AccessControlContext context)
    {
        // TODO: Refactor code to inject metastore headers using AccessControlContext instead of empty()
        return new MetastoreContext(
                identity,
                context.getQueryId().getId(),
                context.getClientInfo(),
                context.getClientTags(),
                context.getSource(),
                Optional.empty(),
                false,
                HiveColumnConverterProvider.DEFAULT_COLUMN_CONVERTER_PROVIDER,
                context.getWarningCollector(),
                context.getRuntimeStats());
    }

    private boolean isAdmin(ConnectorTransactionHandle transaction, ConnectorIdentity identity, MetastoreContext metastoreContext)
    {
        return getMetastore(transaction)
                .map(metastore -> isRoleEnabled(identity, (PrestoPrincipal p) -> metastore.listRoleGrants(metastoreContext, p), ADMIN_ROLE_NAME))
                .orElse(false);
    }

    private boolean isDatabaseOwner(ConnectorTransactionHandle transaction, ConnectorIdentity identity, MetastoreContext metastoreContext, String databaseName)
    {
        // all users are "owners" of the default database
        if (DEFAULT_DATABASE_NAME.equalsIgnoreCase(databaseName)) {
            return true;
        }

        if (isAdmin(transaction, identity, metastoreContext)) {
            return true;
        }

        Optional<SemiTransactionalHiveMetastore> metastoreOptional = getMetastore(transaction);
        return metastoreOptional.map(metastore -> {
            Optional<Database> databaseMetadata = metastore.getDatabase(metastoreContext, databaseName);
            if (!databaseMetadata.isPresent()) {
                return false;
            }

            Database database = databaseMetadata.get();

            // a database can be owned by a user or role
            if (database.getOwnerType() == USER && identity.getUser().equals(database.getOwnerName())) {
                return true;
            }
            if (database.getOwnerType() == ROLE && isRoleEnabled(identity, (PrestoPrincipal p) -> metastore.listRoleGrants(metastoreContext, p), database.getOwnerName())) {
                return true;
            }
            return false;
        }).orElse(false);
    }

    private boolean isTableOwner(ConnectorTransactionHandle transaction, ConnectorIdentity identity, MetastoreContext metastoreContext, SchemaTableName tableName)
    {
        return checkTablePermission(transaction, identity, metastoreContext, tableName, OWNERSHIP, false);
    }

    private boolean checkTablePermission(
            ConnectorTransactionHandle transaction,
            ConnectorIdentity identity,
            MetastoreContext metastoreContext,
            SchemaTableName tableName,
            HivePrivilege requiredPrivilege,
            boolean grantOptionRequired)
    {
        if (isAdmin(transaction, identity, metastoreContext)) {
            return true;
        }

        if (tableName.equals(ROLES)) {
            return false;
        }

        if (INFORMATION_SCHEMA_NAME.equals(tableName.getSchemaName())) {
            return true;
        }

        return getMetastore(transaction)
                .map(metastore -> listEnabledTablePrivileges(metastore, tableName.getSchemaName(), tableName.getTableName(), identity, metastoreContext)
                        .filter(privilegeInfo -> !grantOptionRequired || privilegeInfo.isGrantOption())
                        .anyMatch(privilegeInfo -> privilegeInfo.getHivePrivilege().equals(requiredPrivilege)))
                .orElse(false);
    }

    private boolean hasGrantOptionForPrivilege(ConnectorTransactionHandle transaction, ConnectorIdentity identity, MetastoreContext metastoreContext, Privilege privilege, SchemaTableName tableName)
    {
        if (isAdmin(transaction, identity, metastoreContext)) {
            return true;
        }

        return getMetastore(transaction)
                .map(metastore -> listApplicableTablePrivileges(
                        metastore,
                        identity,
                        metastoreContext,
                        tableName.getSchemaName(),
                        tableName.getTableName(), identity.getUser())
                        .anyMatch(privilegeInfo -> privilegeInfo.getHivePrivilege().equals(toHivePrivilege(privilege)) && privilegeInfo.isGrantOption()))
                .orElse(false);
    }

    private boolean hasAdminOptionForRoles(ConnectorTransactionHandle transaction, ConnectorIdentity identity, MetastoreContext metastoreContext, Set<String> roles)
    {
        if (isAdmin(transaction, identity, metastoreContext)) {
            return true;
        }

        return getMetastore(transaction)
                .map(metastore -> {
                    Set<String> rolesWithGrantOption = listApplicableRoles(new PrestoPrincipal(USER, identity.getUser()), (PrestoPrincipal p) -> metastore.listRoleGrants(metastoreContext, p))
                            .filter(RoleGrant::isGrantable)
                            .map(RoleGrant::getRoleName)
                            .collect(toSet());
                    return rolesWithGrantOption.containsAll(roles);
                })
                .orElse(false);
    }

    private boolean hasAnyTablePermission(ConnectorTransactionHandle transaction, ConnectorIdentity identity, MetastoreContext metastoreContext, SchemaTableName tableName)
    {
        if (isAdmin(transaction, identity, metastoreContext)) {
            return true;
        }

        if (tableName.equals(ROLES)) {
            return false;
        }

        if (INFORMATION_SCHEMA_NAME.equals(tableName.getSchemaName())) {
            return true;
        }

        return getMetastore(transaction)
                .map(metastore -> listEnabledTablePrivileges(metastore, tableName.getSchemaName(), tableName.getTableName(), identity, metastoreContext)
                .anyMatch(privilegeInfo -> true))
                .orElse(false);
    }

    private Optional<SemiTransactionalHiveMetastore> getMetastore(ConnectorTransactionHandle transaction)
    {
        TransactionalMetadata metadata = hiveTransactionManager.get(transaction);
        // In some scenarios, for example, when a statement in a non-autocommit transaction fails,
        // the corresponding transaction metadata could be null.
        return Optional.ofNullable(metadata).map(TransactionalMetadata::getMetastore);
    }
}
