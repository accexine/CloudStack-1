package com.cloud.baremetal.networkservice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.baremetal.database.BaremetalDhcpVO;
import com.cloud.baremetal.database.BaremetalPxeVO;
import com.cloud.baremetal.manager.BaremetalDhcpManager;
import com.cloud.dc.Pod;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.component.Inject;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.SearchCriteria2;
import com.cloud.utils.db.SearchCriteriaService;
import com.cloud.utils.db.Transaction;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.VirtualMachineProfile;

@Local(value = NetworkElement.class)
public class BaremetalDhcpElement extends AdapterBase implements DhcpServiceProvider {
    private static final Logger s_logger = Logger.getLogger(BaremetalDhcpElement.class);
    private static final Map<Service, Map<Capability, String>> capabilities;
    private static final Provider provider = new Provider("BaremetalDhcpProvider", true);
    
    @Inject NicDao _nicDao;
    @Inject BaremetalDhcpManager _dhcpMgr;
    
    static {
        Capability cap = new Capability(BaremetalDhcpManager.BAREMETAL_DHCP_SERVICE_CAPABITLITY);
        Map<Capability, String> baremetalCaps = new HashMap<Capability, String>();
        baremetalCaps.put(cap, null);
        capabilities = new HashMap<Service, Map<Capability, String>>();
        capabilities.put(Service.Dhcp, baremetalCaps);
    }
    
    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return capabilities;
    }

    @Override
    public Provider getProvider() {
        return provider;
    }

    private boolean canHandle(DeployDestination dest, TrafficType trafficType, GuestType networkType) {
        Pod pod = dest.getPod();
        if (pod != null && dest.getDataCenter().getNetworkType() == NetworkType.Basic && trafficType == TrafficType.Guest) {
            SearchCriteriaService<BaremetalDhcpVO, BaremetalDhcpVO> sc = SearchCriteria2.create(BaremetalDhcpVO.class);
            sc.addAnd(sc.getEntity().getPodId(), Op.EQ, pod.getId());
            return sc.find() != null;
        }
        
        return false;
    }
    
    @Override
    public boolean implement(Network network, NetworkOffering offering, DeployDestination dest, ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        if (offering.isSystemOnly() || !canHandle(dest, offering.getTrafficType(), network.getGuestType())) {
            s_logger.debug("BaremetalDhcpElement can not handle networkoffering: " + offering.getName());
            return false;
        }
        return true;
    }

    @Override
    public boolean prepare(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, DeployDestination dest,
            ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        Host host = dest.getHost();
        if (vm.getType() != Type.User || vm.getHypervisorType() != HypervisorType.BareMetal) {
            return false;
        }
        
        Transaction txn = Transaction.currentTxn();
        txn.start();
        nic.setMacAddress(host.getPrivateMacAddress());
        NicVO vo = _nicDao.findById(nic.getId());
        assert vo != null : "Where ths nic " + nic.getId() + " going???";
        vo.setMacAddress(nic.getMacAddress());
        _nicDao.update(vo.getId(), vo);
        txn.commit();
        return true;
    }

    @Override
    public boolean release(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean shutdown(Network network, ReservationContext context, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean destroy(Network network) throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean isReady(PhysicalNetworkServiceProvider provider) {
        return true;
    }

    @Override
    public boolean shutdownProviderInstances(PhysicalNetworkServiceProvider provider, ReservationContext context) throws ConcurrentOperationException,
            ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean canEnableIndividualServices() {
        return false;
    }

    @Override
    public boolean verifyServicesCombination(List<String> services) {
        return true;
    }

    @Override
    public boolean addDhcpEntry(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, DeployDestination dest,
            ReservationContext context) throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        if (vm.getHypervisorType() != HypervisorType.BareMetal || !canHandle(dest, network.getTrafficType(), network.getGuestType())) {
            return false;
        }
        return _dhcpMgr.addVirtualMachineIntoNetwork(network, nic, vm, dest, context);
    }

}