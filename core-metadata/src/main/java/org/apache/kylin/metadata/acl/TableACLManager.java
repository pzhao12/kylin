/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.metadata.acl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.persistence.JsonSerializer;
import org.apache.kylin.common.persistence.ResourceStore;
import org.apache.kylin.common.persistence.Serializer;
import org.apache.kylin.metadata.cachesync.Broadcaster;
import org.apache.kylin.metadata.cachesync.CaseInsensitiveStringCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
public class TableACLManager {

    private static final Logger logger = LoggerFactory.getLogger(TableACLManager.class);

    private static final Serializer<TableACL> TABLE_ACL_SERIALIZER = new JsonSerializer<>(TableACL.class);
    private static final String DIR_PREFIX = "/table_acl/";

    // static cached instances
    private static final ConcurrentMap<KylinConfig, TableACLManager> CACHE = new ConcurrentHashMap<>();

    public static TableACLManager getInstance(KylinConfig config) {
        TableACLManager r = CACHE.get(config);
        if (r != null) {
            return r;
        }

        synchronized (TableACLManager.class) {
            r = CACHE.get(config);
            if (r != null) {
                return r;
            }
            try {
                r = new TableACLManager(config);
                CACHE.put(config, r);
                if (CACHE.size() > 1) {
                    logger.warn("More than one singleton exist");
                }
                return r;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to init CubeDescManager from " + config, e);
            }
        }
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static void clearCache(KylinConfig kylinConfig) {
        if (kylinConfig != null)
            CACHE.remove(kylinConfig);
    }

    // ============================================================================

    private KylinConfig config;
    // user ==> TableACL
    private CaseInsensitiveStringCache<TableACL> tableACLMap;

    public TableACLManager(KylinConfig config) throws IOException {
        logger.info("Initializing TableACLManager with config " + config);
        this.config = config;
        this.tableACLMap = new CaseInsensitiveStringCache<>(config, "table_acl");
        loadAllTableACL();
        Broadcaster.getInstance(config).registerListener(new TableACLSyncListener(), "table_acl");
    }

    private class TableACLSyncListener extends Broadcaster.Listener {
        @Override
        public void onClearAll(Broadcaster broadcaster) throws IOException {
            clearCache();
        }

        @Override
        public void onEntityChange(Broadcaster broadcaster, String entity, Broadcaster.Event event, String cacheKey) throws IOException {
            reloadTableACL(cacheKey);
        }
    }

    public KylinConfig getConfig() {
        return config;
    }

    public ResourceStore getStore() {
        return ResourceStore.getStore(this.config);
    }

    public TableACL getTableACLByCache(String project){
        TableACL tableACL = tableACLMap.get(project);
        if (tableACL == null) {
            return new TableACL();
        }
        return tableACL;
    }

    private void loadAllTableACL() throws IOException {
        ResourceStore store = getStore();
        List<String> paths = store.collectResourceRecursively("/table_acl", "");
        final int prefixLen = DIR_PREFIX.length();
        for (String path : paths) {
            String project = path.substring(prefixLen, path.length());
            reloadTableACL(project);
        }
        logger.info("Loading table ACL from folder " + store.getReadableResourcePath("/table_acl"));
    }

    private void reloadTableACL(String project) throws IOException {
        TableACL tableACLRecord = getTableACL(project);
        tableACLMap.putLocal(project, tableACLRecord);
    }

    private TableACL getTableACL(String project) throws IOException {
        String path = DIR_PREFIX + project;
        TableACL tableACLRecord = getStore().getResource(path, TableACL.class, TABLE_ACL_SERIALIZER);
        if (tableACLRecord == null || tableACLRecord.getUserTableBlackList() == null) {
            return new TableACL();
        }
        return tableACLRecord;
    }

    public void addTableACL(String project, String username, String table) throws IOException {
        String path = DIR_PREFIX + project;
        TableACL tableACL = getTableACL(project).add(username, table);
        getStore().putResource(path, tableACL, System.currentTimeMillis(), TABLE_ACL_SERIALIZER);
        tableACLMap.put(project, tableACL);
    }

    public void deleteTableACL(String project, String username, String table) throws IOException {
        String path = DIR_PREFIX + project;
        TableACL tableACL = getTableACL(project).delete(username, table);
        getStore().putResource(path, tableACL, System.currentTimeMillis(), TABLE_ACL_SERIALIZER);
        tableACLMap.put(project, tableACL);
    }

    public void deleteTableACL(String project, String username) throws IOException {
        String path = DIR_PREFIX + project;
        TableACL tableACL = getTableACL(project).delete(username);
        getStore().putResource(path, tableACL, System.currentTimeMillis(), TABLE_ACL_SERIALIZER);
        tableACLMap.put(project, tableACL);
    }

    public void deleteTableACLByTbl(String project, String table) throws IOException {
        String path = DIR_PREFIX + project;
        TableACL tableACL = getTableACL(project).deleteByTbl(table);
        getStore().putResource(path, tableACL, System.currentTimeMillis(), TABLE_ACL_SERIALIZER);
        tableACLMap.put(project, tableACL);
    }
}
