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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.entity.rebind;

import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.BrooklynLogging;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.rebind.persister.PersistenceActivityMetrics;
import brooklyn.management.ha.ManagementNodeState;
import brooklyn.management.internal.EntityManagerInternal;
import brooklyn.management.internal.LocationManagerInternal;
import brooklyn.management.internal.ManagementTransitionInfo.ManagementTransitionMode;
import brooklyn.mementos.BrooklynMementoPersister;
import brooklyn.util.text.Strings;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;

/**
 * Does an un-bind (if necessary) and re-bind for a subset of items.  
 */
public class InitialFullRebindIteration extends RebindIteration {

    private static final Logger LOG = LoggerFactory.getLogger(InitialFullRebindIteration.class);
    
    public InitialFullRebindIteration(RebindManagerImpl rebindManager, 
            ManagementNodeState mode,
            ClassLoader classLoader, RebindExceptionHandler exceptionHandler,
            Semaphore rebindActive, AtomicInteger readOnlyRebindCount, PersistenceActivityMetrics rebindMetrics, BrooklynMementoPersister persistenceStoreAccess
            ) {
        super(rebindManager, mode, classLoader, exceptionHandler, rebindActive, readOnlyRebindCount, rebindMetrics, persistenceStoreAccess);
    }

    @Override
    protected boolean isRebindingActiveAgain() {
        return false;
    }
    
    protected void doRun() throws Exception {
        LOG.debug("Rebinding ("+mode+
            (readOnlyRebindCount.get()>Integer.MIN_VALUE ? ", iteration "+readOnlyRebindCount : "")+
            ") from "+rebindManager.getPersister().getBackingStoreDescription()+"...");

        loadManifestFiles();
        rebuildCatalog();
        instantiateLocationsAndEntities();
        instantiateMementos();
        instantiateAdjuncts(instantiator); 
        reconstructEverything();
        associateAdjunctsWithEntities();
        manageTheObjects();
        finishingUp();
    }

    protected void loadManifestFiles() throws Exception {
        checkEnteringPhase(1);
        Preconditions.checkState(mementoRawData==null && mementoManifest==null, "Memento data should not yet be set when calling this");
        
        mementoRawData = persistenceStoreAccess.loadMementoRawData(exceptionHandler);
        
        // TODO building the manifests should be part of this class (or parent)
        // it does not have anything to do with the persistence store!
        mementoManifest = persistenceStoreAccess.loadMementoManifest(mementoRawData, exceptionHandler);

        determineStateFromManifestFiles();
        
        if (mode!=ManagementNodeState.HOT_STANDBY && mode!=ManagementNodeState.HOT_BACKUP) {
            if (!isEmpty) { 
                LOG.info("Rebinding from "+getPersister().getBackingStoreDescription()+" for "+Strings.toLowerCase(Strings.toString(mode))+" "+managementContext.getManagementNodeId()+"...");
            } else {
                LOG.info("Rebind check: no existing state; will persist new items to "+getPersister().getBackingStoreDescription());
            }

            if (!managementContext.getEntityManager().getEntities().isEmpty() || !managementContext.getLocationManager().getLocations().isEmpty()) {
                // this is discouraged if we were already master
                Entity anEntity = Iterables.getFirst(managementContext.getEntityManager().getEntities(), null);
                if (anEntity!=null && !((EntityInternal)anEntity).getManagementSupport().isReadOnly()) {
                    overwritingMaster = true;
                    LOG.warn("Rebind requested for "+mode+" node "+managementContext.getManagementNodeId()+" "
                        + "when it already has active state; discouraged, "
                        + "will likely overwrite: "+managementContext.getEntityManager().getEntities()+" and "+managementContext.getLocationManager().getLocations()+" and more");
                }
            }
        }
    }

    @Override
    protected void cleanupOldLocations(Set<String> oldLocations) {
        LocationManagerInternal locationManager = (LocationManagerInternal)managementContext.getLocationManager();
        if (!oldLocations.isEmpty()) BrooklynLogging.log(LOG, overwritingMaster ? BrooklynLogging.LoggingLevel.WARN : BrooklynLogging.LoggingLevel.DEBUG, 
            "Destroying unused locations on rebind: "+oldLocations);
        for (String oldLocationId: oldLocations) {
           locationManager.unmanage(locationManager.getLocation(oldLocationId), ManagementTransitionMode.REBINDING_DESTROYED); 
        }
    }

    @Override
    protected void cleanupOldEntities(Set<String> oldEntities) {
        EntityManagerInternal entityManager = (EntityManagerInternal)managementContext.getEntityManager();
        if (!oldEntities.isEmpty()) BrooklynLogging.log(LOG, overwritingMaster ? BrooklynLogging.LoggingLevel.WARN : BrooklynLogging.LoggingLevel.DEBUG, 
            "Destroying unused entities on rebind: "+oldEntities);
        for (String oldEntityId: oldEntities) {
           entityManager.unmanage(entityManager.getEntity(oldEntityId), ManagementTransitionMode.REBINDING_DESTROYED); 
        }
    }

}
