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
package io.prestosql.plugin.base.security;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.CatalogSchemaTableName;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.Privilege;
import io.prestosql.spi.security.SystemAccessControl;
import io.prestosql.spi.security.SystemAccessControlFactory;
import io.prestosql.spi.security.SystemSecurityContext;

import java.nio.file.Paths;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Suppliers.memoizeWithExpiration;
import static io.prestosql.plugin.base.JsonUtils.parseJson;
import static io.prestosql.plugin.base.security.FileBasedAccessControlConfig.SECURITY_CONFIG_FILE;
import static io.prestosql.plugin.base.security.FileBasedAccessControlConfig.SECURITY_REFRESH_PERIOD;
import static io.prestosql.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.prestosql.spi.security.AccessDeniedException.denyCatalogAccess;
import static io.prestosql.spi.security.AccessDeniedException.denySetUser;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class FileBasedSystemAccessControl
        implements SystemAccessControl
{
    private static final Logger log = Logger.get(FileBasedSystemAccessControl.class);

    public static final String NAME = "file";

    private final List<CatalogAccessControlRule> catalogRules;
    private final Optional<List<PrincipalUserMatchRule>> principalUserMatchRules;

    private FileBasedSystemAccessControl(List<CatalogAccessControlRule> catalogRules, Optional<List<PrincipalUserMatchRule>> principalUserMatchRules)
    {
        this.catalogRules = catalogRules;
        this.principalUserMatchRules = principalUserMatchRules;
    }

    public static class Factory
            implements SystemAccessControlFactory
    {
        @Override
        public String getName()
        {
            return NAME;
        }

        @Override
        public SystemAccessControl create(Map<String, String> config)
        {
            requireNonNull(config, "config is null");

            String configFileName = config.get(SECURITY_CONFIG_FILE);
            checkState(configFileName != null, "Security configuration must contain the '%s' property", SECURITY_CONFIG_FILE);

            if (config.containsKey(SECURITY_REFRESH_PERIOD)) {
                Duration refreshPeriod;
                try {
                    refreshPeriod = Duration.valueOf(config.get(SECURITY_REFRESH_PERIOD));
                }
                catch (IllegalArgumentException e) {
                    throw invalidRefreshPeriodException(config, configFileName);
                }
                if (refreshPeriod.toMillis() == 0) {
                    throw invalidRefreshPeriodException(config, configFileName);
                }
                return ForwardingSystemAccessControl.of(memoizeWithExpiration(
                        () -> {
                            log.info("Refreshing system access control from %s", configFileName);
                            return create(configFileName);
                        },
                        refreshPeriod.toMillis(),
                        MILLISECONDS));
            }
            return create(configFileName);
        }

        private PrestoException invalidRefreshPeriodException(Map<String, String> config, String configFileName)
        {
            return new PrestoException(
                    CONFIGURATION_INVALID,
                    format("Invalid duration value '%s' for property '%s' in '%s'", config.get(SECURITY_REFRESH_PERIOD), SECURITY_REFRESH_PERIOD, configFileName));
        }

        private SystemAccessControl create(String configFileName)
        {
            FileBasedSystemAccessControlRules rules = parseJson(Paths.get(configFileName), FileBasedSystemAccessControlRules.class);

            ImmutableList.Builder<CatalogAccessControlRule> catalogRulesBuilder = ImmutableList.builder();
            catalogRulesBuilder.addAll(rules.getCatalogRules());

            // Hack to allow Presto Admin to access the "system" catalog for retrieving server status.
            // todo Change userRegex from ".*" to one particular user that Presto Admin will be restricted to run as
            catalogRulesBuilder.add(new CatalogAccessControlRule(
                    true,
                    Optional.of(Pattern.compile(".*")),
                    Optional.of(Pattern.compile("system"))));

            return new FileBasedSystemAccessControl(catalogRulesBuilder.build(), rules.getPrincipalUserMatchRules());
        }
    }

    @Override
    public void checkCanSetUser(Optional<Principal> principal, String userName)
    {
        requireNonNull(principal, "principal is null");
        requireNonNull(userName, "userName is null");

        if (!principalUserMatchRules.isPresent()) {
            return;
        }

        if (!principal.isPresent()) {
            denySetUser(principal, userName);
        }

        String principalName = principal.get().getName();

        for (PrincipalUserMatchRule rule : principalUserMatchRules.get()) {
            Optional<Boolean> allowed = rule.match(principalName, userName);
            if (allowed.isPresent()) {
                if (allowed.get()) {
                    return;
                }
                denySetUser(principal, userName);
            }
        }

        denySetUser(principal, userName);
    }

    @Override
    public void checkCanSetSystemSessionProperty(SystemSecurityContext context, String propertyName)
    {
    }

    @Override
    public void checkCanAccessCatalog(SystemSecurityContext context, String catalogName)
    {
        if (!canAccessCatalog(context.getIdentity(), catalogName)) {
            denyCatalogAccess(catalogName);
        }
    }

    @Override
    public Set<String> filterCatalogs(SystemSecurityContext context, Set<String> catalogs)
    {
        ImmutableSet.Builder<String> filteredCatalogs = ImmutableSet.builder();
        for (String catalog : catalogs) {
            if (canAccessCatalog(context.getIdentity(), catalog)) {
                filteredCatalogs.add(catalog);
            }
        }
        return filteredCatalogs.build();
    }

    private boolean canAccessCatalog(Identity identity, String catalogName)
    {
        for (CatalogAccessControlRule rule : catalogRules) {
            Optional<Boolean> allowed = rule.match(identity.getUser(), catalogName);
            if (allowed.isPresent()) {
                return allowed.get();
            }
        }
        return false;
    }

    @Override
    public void checkCanCreateSchema(SystemSecurityContext context, CatalogSchemaName schema)
    {
    }

    @Override
    public void checkCanDropSchema(SystemSecurityContext context, CatalogSchemaName schema)
    {
    }

    @Override
    public void checkCanRenameSchema(SystemSecurityContext context, CatalogSchemaName schema, String newSchemaName)
    {
    }

    @Override
    public void checkCanShowSchemas(SystemSecurityContext context, String catalogName)
    {
    }

    @Override
    public Set<String> filterSchemas(SystemSecurityContext context, String catalogName, Set<String> schemaNames)
    {
        if (!canAccessCatalog(context.getIdentity(), catalogName)) {
            return ImmutableSet.of();
        }

        return schemaNames;
    }

    @Override
    public void checkCanCreateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public void checkCanDropTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public void checkCanRenameTable(SystemSecurityContext context, CatalogSchemaTableName table, CatalogSchemaTableName newTable)
    {
    }

    @Override
    public void checkCanSetTableComment(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public void checkCanShowTablesMetadata(SystemSecurityContext context, CatalogSchemaName schema)
    {
    }

    @Override
    public Set<SchemaTableName> filterTables(SystemSecurityContext context, String catalogName, Set<SchemaTableName> tableNames)
    {
        if (!canAccessCatalog(context.getIdentity(), catalogName)) {
            return ImmutableSet.of();
        }

        return tableNames;
    }

    @Override
    public void checkCanShowColumnsMetadata(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public List<ColumnMetadata> filterColumns(SystemSecurityContext context, CatalogSchemaTableName tableName, List<ColumnMetadata> columns)
    {
        if (!canAccessCatalog(context.getIdentity(), tableName.getCatalogName())) {
            return ImmutableList.of();
        }

        return columns;
    }

    @Override
    public void checkCanAddColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public void checkCanDropColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public void checkCanRenameColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
    }

    @Override
    public void checkCanInsertIntoTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public void checkCanDeleteFromTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public void checkCanCreateView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
    }

    @Override
    public void checkCanDropView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
    }

    @Override
    public void checkCanSetCatalogSessionProperty(SystemSecurityContext context, String catalogName, String propertyName)
    {
    }

    @Override
    public void checkCanGrantTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, PrestoPrincipal grantee, boolean withGrantOption)
    {
    }

    @Override
    public void checkCanRevokeTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, PrestoPrincipal revokee, boolean grantOptionFor)
    {
    }

    @Override
    public void checkCanShowRoles(SystemSecurityContext context, String catalogName)
    {
    }
}
