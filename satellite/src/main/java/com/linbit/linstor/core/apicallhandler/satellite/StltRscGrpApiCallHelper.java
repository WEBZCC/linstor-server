package com.linbit.linstor.core.apicallhandler.satellite;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.linstor.annotation.SystemContext;
import com.linbit.ValueOutOfRangeException;
import com.linbit.linstor.VolumeGroupDataSatelliteFactory;
import com.linbit.linstor.api.interfaces.AutoSelectFilterApi;
import com.linbit.linstor.core.ControllerPeerConnector;
import com.linbit.linstor.core.CoreModule;
import com.linbit.linstor.core.DeviceManager;
import com.linbit.linstor.core.identifier.ResourceGroupName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.AutoSelectorConfigData;
import com.linbit.linstor.core.objects.ResourceGroup;
import com.linbit.linstor.core.objects.ResourceGroup.RscGrpApi;
import com.linbit.linstor.core.objects.ResourceGroupData;
import com.linbit.linstor.core.objects.ResourceGroupDataSatelliteFactory;
import com.linbit.linstor.core.objects.VolumeGroup;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.transaction.TransactionMgr;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.util.Map;
import java.util.TreeMap;

@Singleton
class StltRscGrpApiCallHelper
{
    private final ErrorReporter errorReporter;
    private final AccessContext apiCtx;
    private final DeviceManager deviceManager;
    private final ControllerPeerConnector controllerPeerConnector;
    private final CoreModule.ResourceGroupMap rscGrpMap;
    private final ResourceGroupDataSatelliteFactory resourceGroupDataFactory;
    private final VolumeGroupDataSatelliteFactory volumeGroupDataFactory;
    private final Provider<TransactionMgr> transMgrProvider;

    @Inject
    public StltRscGrpApiCallHelper(
        ErrorReporter errorReporterRef,
        @SystemContext AccessContext apiCtxRef,
        DeviceManager deviceManagerRef,
        ControllerPeerConnector controllerPeerConnectorRef,
        CoreModule.ResourceGroupMap rscGrpMapRef,
        ResourceGroupDataSatelliteFactory resourceGroupDataFactoryRef,
        VolumeGroupDataSatelliteFactory volumeGroupDataFactoryRef,
        Provider<TransactionMgr> transMgrProviderRef
    )
    {
        errorReporter = errorReporterRef;
        apiCtx = apiCtxRef;
        deviceManager = deviceManagerRef;
        controllerPeerConnector = controllerPeerConnectorRef;
        rscGrpMap = rscGrpMapRef;
        resourceGroupDataFactory = resourceGroupDataFactoryRef;
        volumeGroupDataFactory = volumeGroupDataFactoryRef;
        transMgrProvider = transMgrProviderRef;
    }

    public ResourceGroup mergeResourceGroup(RscGrpApi rscGrpApiRef)
        throws InvalidNameException, AccessDeniedException, DatabaseException
    {
        ResourceGroupName rscGrpName = new ResourceGroupName(rscGrpApiRef.getName());
        AutoSelectFilterApi autoPlaceConfigPojo = rscGrpApiRef.getAutoSelectFilter();

        ResourceGroupData rscGrp = (ResourceGroupData) rscGrpMap.get(rscGrpName);
        if (rscGrp == null)
        {
            rscGrp = resourceGroupDataFactory.getInstanceSatellite(
                rscGrpApiRef.getUuid(),
                rscGrpName,
                rscGrpApiRef.getDescription(),
                autoPlaceConfigPojo.getLayerStackList(),
                autoPlaceConfigPojo.getReplicaCount(),
                autoPlaceConfigPojo.getStorPoolNameStr(),
                autoPlaceConfigPojo.getDoNotPlaceWithRscList(),
                autoPlaceConfigPojo.getDoNotPlaceWithRscRegex(),
                autoPlaceConfigPojo.getReplicasOnSameList(),
                autoPlaceConfigPojo.getReplicasOnDifferentList(),
                autoPlaceConfigPojo.getProviderList(),
                autoPlaceConfigPojo.getDisklessOnRemaining()
            );
            rscGrp.getProps(apiCtx).map().putAll(rscGrpApiRef.getProps());
            rscGrpMap.put(rscGrpName, rscGrp);
        }
        else
        {
            Map<String, String> targetProps = rscGrp.getProps(apiCtx).map();
            targetProps.clear();
            targetProps.putAll(rscGrpApiRef.getProps());

            rscGrp.setDescription(apiCtx, rscGrpApiRef.getDescription());

            AutoSelectorConfigData autoPlaceConfig = (AutoSelectorConfigData) rscGrp.getAutoPlaceConfig();

            autoPlaceConfig.applyChanges(autoPlaceConfigPojo);
        }

        Map<VolumeNumber, VolumeGroup> vlmGrpsToDelete = new TreeMap<>();
        // add all current volume group and delete them again if they are still in the vlmGrpApiList
        for (VolumeGroup vlmGrp : rscGrp.getVolumeGroups(apiCtx))
        {
            vlmGrpsToDelete.put(vlmGrp.getVolumeNumber(), vlmGrp);
        }

        try
        {
            for (VolumeGroup.VlmGrpApi vlmGrpApi : rscGrpApiRef.getVlmGrpList())
            {
                VolumeNumber vlmNr = new VolumeNumber(vlmGrpApi.getVolumeNr());
                VolumeGroup vlmGrp = vlmGrpsToDelete.remove(vlmNr);
                if (vlmGrp == null)
                {
                    vlmGrp = volumeGroupDataFactory.getInstanceSatellite(
                        vlmGrpApi.getUUID(),
                        rscGrp,
                        vlmNr
                    );
                }
                Props vlmGrpProps = vlmGrp.getProps(apiCtx);
                vlmGrpProps.clear();
                vlmGrpProps.map().putAll(vlmGrpApi.getProps());
            }

            for (VolumeNumber vlmNr : vlmGrpsToDelete.keySet())
            {
                rscGrp.deleteVolumeGroup(apiCtx, vlmNr);
            }
        }
        catch (ValueOutOfRangeException exc)
        {
            throw new ImplementationError(exc);
        }

        return rscGrp;
    }
}