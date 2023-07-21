package cz.xtf.core.openshift;

import static cz.xtf.core.config.OpenShiftConfig.routeDomain;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import cz.xtf.core.config.WaitingConfig;
import cz.xtf.core.event.EventList;
import cz.xtf.core.openshift.crd.CustomResourceDefinitionContextProvider;
import cz.xtf.core.waiting.SimpleWaiter;
import cz.xtf.core.waiting.Waiter;
import io.fabric8.kubernetes.api.builder.Visitor;
import io.fabric8.kubernetes.api.model.APIGroup;
import io.fabric8.kubernetes.api.model.APIGroupList;
import io.fabric8.kubernetes.api.model.APIResourceList;
import io.fabric8.kubernetes.api.model.APIService;
import io.fabric8.kubernetes.api.model.APIServiceList;
import io.fabric8.kubernetes.api.model.Binding;
import io.fabric8.kubernetes.api.model.ComponentStatus;
import io.fabric8.kubernetes.api.model.ComponentStatusList;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointsList;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeList;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.PersistentVolumeList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerList;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaList;
import io.fabric8.kubernetes.api.model.RootPaths;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountList;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.authentication.TokenReview;
import io.fabric8.kubernetes.api.model.autoscaling.v1.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.certificates.v1beta1.CertificateSigningRequest;
import io.fabric8.kubernetes.api.model.certificates.v1beta1.CertificateSigningRequestList;
import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseList;
import io.fabric8.kubernetes.api.model.node.v1beta1.RuntimeClass;
import io.fabric8.kubernetes.api.model.node.v1beta1.RuntimeClassList;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.Subject;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.kubernetes.client.AdmissionRegistrationAPIGroupDSL;
import io.fabric8.kubernetes.client.ApiVisitor;
import io.fabric8.kubernetes.client.Client;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.RequestConfig;
import io.fabric8.kubernetes.client.VersionInfo;
import io.fabric8.kubernetes.client.dsl.ApiextensionsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AuthenticationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AuthorizationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AutoscalingAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.CertificatesAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.DiscoveryAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.DynamicResourceAllocationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.EventingAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.ExtensionsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FlowControlAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FunctionCallable;
import io.fabric8.kubernetes.client.dsl.Gettable;
import io.fabric8.kubernetes.client.dsl.InOutCreateable;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.MetricAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Nameable;
import io.fabric8.kubernetes.client.dsl.NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable;
import io.fabric8.kubernetes.client.dsl.Namespaceable;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.NamespacedInOutCreateable;
import io.fabric8.kubernetes.client.dsl.NetworkAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ParameterMixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.PolicyAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.RbacAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.SchedulingAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.fabric8.kubernetes.client.dsl.StorageAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.V1APIGroupDSL;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.internal.HasMetadataOperationsImpl;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectorBuilder;
import io.fabric8.kubernetes.client.extended.run.RunOperations;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import io.fabric8.openshift.api.model.BrokerTemplateInstance;
import io.fabric8.openshift.api.model.BrokerTemplateInstanceList;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigList;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.api.model.BuildRequest;
import io.fabric8.openshift.api.model.BuildRequestBuilder;
import io.fabric8.openshift.api.model.ClusterNetwork;
import io.fabric8.openshift.api.model.ClusterNetworkList;
import io.fabric8.openshift.api.model.ClusterRole;
import io.fabric8.openshift.api.model.ClusterRoleBinding;
import io.fabric8.openshift.api.model.ClusterRoleBindingList;
import io.fabric8.openshift.api.model.ClusterRoleList;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigList;
import io.fabric8.openshift.api.model.EgressNetworkPolicy;
import io.fabric8.openshift.api.model.EgressNetworkPolicyList;
import io.fabric8.openshift.api.model.Group;
import io.fabric8.openshift.api.model.GroupList;
import io.fabric8.openshift.api.model.HelmChartRepository;
import io.fabric8.openshift.api.model.HelmChartRepositoryList;
import io.fabric8.openshift.api.model.HostSubnet;
import io.fabric8.openshift.api.model.HostSubnetList;
import io.fabric8.openshift.api.model.Identity;
import io.fabric8.openshift.api.model.IdentityList;
import io.fabric8.openshift.api.model.Image;
import io.fabric8.openshift.api.model.ImageList;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamImage;
import io.fabric8.openshift.api.model.ImageStreamImport;
import io.fabric8.openshift.api.model.ImageStreamList;
import io.fabric8.openshift.api.model.ImageStreamMapping;
import io.fabric8.openshift.api.model.ImageStreamTag;
import io.fabric8.openshift.api.model.ImageStreamTagList;
import io.fabric8.openshift.api.model.ImageTag;
import io.fabric8.openshift.api.model.ImageTagList;
import io.fabric8.openshift.api.model.LocalResourceAccessReview;
import io.fabric8.openshift.api.model.LocalSubjectAccessReview;
import io.fabric8.openshift.api.model.NetNamespace;
import io.fabric8.openshift.api.model.NetNamespaceList;
import io.fabric8.openshift.api.model.OAuthAccessToken;
import io.fabric8.openshift.api.model.OAuthAccessTokenList;
import io.fabric8.openshift.api.model.OAuthAuthorizeToken;
import io.fabric8.openshift.api.model.OAuthAuthorizeTokenList;
import io.fabric8.openshift.api.model.OAuthClient;
import io.fabric8.openshift.api.model.OAuthClientAuthorization;
import io.fabric8.openshift.api.model.OAuthClientAuthorizationList;
import io.fabric8.openshift.api.model.OAuthClientList;
import io.fabric8.openshift.api.model.PodSecurityPolicyReview;
import io.fabric8.openshift.api.model.PodSecurityPolicySelfSubjectReview;
import io.fabric8.openshift.api.model.PodSecurityPolicySubjectReview;
import io.fabric8.openshift.api.model.Project;
import io.fabric8.openshift.api.model.ProjectRequest;
import io.fabric8.openshift.api.model.ProjectRequestBuilder;
import io.fabric8.openshift.api.model.RangeAllocation;
import io.fabric8.openshift.api.model.RangeAllocationList;
import io.fabric8.openshift.api.model.ResourceAccessReview;
import io.fabric8.openshift.api.model.ResourceAccessReviewResponse;
import io.fabric8.openshift.api.model.RoleBindingList;
import io.fabric8.openshift.api.model.RoleBindingRestriction;
import io.fabric8.openshift.api.model.RoleBindingRestrictionList;
import io.fabric8.openshift.api.model.RoleList;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.api.model.RouteSpecBuilder;
import io.fabric8.openshift.api.model.SecurityContextConstraints;
import io.fabric8.openshift.api.model.SecurityContextConstraintsList;
import io.fabric8.openshift.api.model.SelfSubjectRulesReview;
import io.fabric8.openshift.api.model.SubjectAccessReview;
import io.fabric8.openshift.api.model.SubjectAccessReviewResponse;
import io.fabric8.openshift.api.model.SubjectRulesReview;
import io.fabric8.openshift.api.model.Template;
import io.fabric8.openshift.api.model.TemplateInstance;
import io.fabric8.openshift.api.model.TemplateInstanceList;
import io.fabric8.openshift.api.model.TemplateList;
import io.fabric8.openshift.api.model.User;
import io.fabric8.openshift.api.model.UserIdentityMapping;
import io.fabric8.openshift.api.model.UserList;
import io.fabric8.openshift.api.model.UserOAuthAccessToken;
import io.fabric8.openshift.api.model.UserOAuthAccessTokenList;
import io.fabric8.openshift.api.model.miscellaneous.apiserver.v1.APIRequestCount;
import io.fabric8.openshift.api.model.miscellaneous.apiserver.v1.APIRequestCountList;
import io.fabric8.openshift.api.model.miscellaneous.cloudcredential.v1.CredentialsRequest;
import io.fabric8.openshift.api.model.miscellaneous.cloudcredential.v1.CredentialsRequestList;
import io.fabric8.openshift.api.model.miscellaneous.cncf.cni.v1.NetworkAttachmentDefinition;
import io.fabric8.openshift.api.model.miscellaneous.cncf.cni.v1.NetworkAttachmentDefinitionList;
import io.fabric8.openshift.api.model.miscellaneous.imageregistry.operator.v1.ConfigList;
import io.fabric8.openshift.api.model.miscellaneous.metal3.v1alpha1.BareMetalHost;
import io.fabric8.openshift.api.model.miscellaneous.metal3.v1alpha1.BareMetalHostList;
import io.fabric8.openshift.api.model.miscellaneous.network.operator.v1.EgressRouter;
import io.fabric8.openshift.api.model.miscellaneous.network.operator.v1.EgressRouterList;
import io.fabric8.openshift.api.model.miscellaneous.network.operator.v1.OperatorPKI;
import io.fabric8.openshift.api.model.miscellaneous.network.operator.v1.OperatorPKIList;
import io.fabric8.openshift.client.NamespacedOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;
import io.fabric8.openshift.client.ParameterValue;
import io.fabric8.openshift.client.dsl.BuildConfigResource;
import io.fabric8.openshift.client.dsl.BuildResource;
import io.fabric8.openshift.client.dsl.DeployableScalableResource;
import io.fabric8.openshift.client.dsl.MachineConfigurationAPIGroupDSL;
import io.fabric8.openshift.client.dsl.NameableCreateOrDeleteable;
import io.fabric8.openshift.client.dsl.OpenShiftClusterAutoscalingAPIGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftConfigAPIGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftConsoleAPIGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftHiveAPIGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftMachineAPIGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftMonitoringAPIGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftOperatorAPIGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftOperatorHubAPIGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftQuotaAPIGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftStorageVersionMigratorApiGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftTunedAPIGroupDSL;
import io.fabric8.openshift.client.dsl.OpenShiftWhereaboutsAPIGroupDSL;
import io.fabric8.openshift.client.dsl.ProjectOperation;
import io.fabric8.openshift.client.dsl.ProjectRequestOperation;
import io.fabric8.openshift.client.dsl.TemplateResource;
import lombok.extern.slf4j.Slf4j;
import rx.Observable;
import rx.observables.StringObservable;

@Slf4j
public class OpenShift implements NamespacedOpenShiftClient {
    private final NamespacedOpenShiftClient delegate;
    private static ServiceLoader<CustomResourceDefinitionContextProvider> crdContextProviderLoader;
    private static volatile String routeSuffix;

    public static final String KEEP_LABEL = "xtf.cz/keep";

    /**
     * Used to cache created Openshift clients for given test case.
     */
    public static final Multimap<String, OpenShift> namespaceToOpenshiftClientMap = Multimaps
            .synchronizedListMultimap(ArrayListMultimap.create());

    /**
     * This label is supposed to be used for any resource created by the XTF to easily distinguish which resources have
     * been created by XTF automation.
     * NOTE: at the moment only place where this is used is for labeling namespaces. Other usages may be added in the future.
     */
    public static final String XTF_MANAGED_LABEL = "xtf.cz/managed";

    /**
     * Autoconfigures the client with the default fabric8 client rules
     *
     * @param namespace set namespace to the Openshift client instance
     * @return this Openshift client instance
     */
    public static OpenShift get(String namespace) {
        Config kubeconfig = Config.autoConfigure(null);

        OpenShiftConfig openShiftConfig = new OpenShiftConfig(kubeconfig);

        setupTimeouts(openShiftConfig);

        if (StringUtils.isNotEmpty(namespace)) {
            openShiftConfig.setNamespace(namespace);
        }

        return get(openShiftConfig);
    }

    public static OpenShift get(Path kubeconfigPath, String namespace) {
        try {
            String kubeconfigContents = new String(Files.readAllBytes(kubeconfigPath), StandardCharsets.UTF_8);
            Config kubeconfig = Config.fromKubeconfig(null, kubeconfigContents, kubeconfigPath.toAbsolutePath().toString());
            OpenShiftConfig openShiftConfig = new OpenShiftConfig(kubeconfig);

            setupTimeouts(openShiftConfig);

            if (StringUtils.isNotEmpty(namespace)) {
                openShiftConfig.setNamespace(namespace);
            }

            return get(openShiftConfig);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static OpenShift get(String masterUrl, String namespace, String username, String password) {
        OpenShiftConfig openShiftConfig = new OpenShiftConfigBuilder()
                .withMasterUrl(masterUrl)
                .withTrustCerts(true)
                .withNamespace(namespace)
                .withUsername(username)
                .withPassword(password)
                .build();

        setupTimeouts(openShiftConfig);

        return get(openShiftConfig);
    }

    public static OpenShift get(String masterUrl, String namespace, String token) {
        OpenShiftConfig openShiftConfig = new OpenShiftConfigBuilder()
                .withMasterUrl(masterUrl)
                .withTrustCerts(true)
                .withNamespace(namespace)
                .withOauthToken(token)
                .build();

        setupTimeouts(openShiftConfig);

        return get(openShiftConfig);
    }

    private static OpenShift get(OpenShiftConfig openShiftConfig) {
        OpenShift openshift;

        // check whether such a client already exists
        Optional<OpenShift> optionalOpenShift = namespaceToOpenshiftClientMap
                .get(openShiftConfig.getNamespace()).stream()
                .filter(oc -> isEqualOpenshiftConfig(openShiftConfig, (OpenShiftConfig) oc.getConfiguration()))
                .findFirst();

        if (optionalOpenShift.isPresent()) {
            return optionalOpenShift.get();
        } else {
            openshift = new OpenShift(openShiftConfig);
            namespaceToOpenshiftClientMap.put(openShiftConfig.getNamespace(), openshift);
        }
        return openshift;
    }

    private static void setupTimeouts(OpenShiftConfig config) {
        config.setBuildTimeout(10 * 60 * 1000);
        config.setRequestTimeout(120_000);
        config.setConnectionTimeout(120_000);
    }

    protected static synchronized ServiceLoader<CustomResourceDefinitionContextProvider> getCRDContextProviders() {
        if (crdContextProviderLoader == null) {
            crdContextProviderLoader = ServiceLoader.load(CustomResourceDefinitionContextProvider.class);
        }
        return crdContextProviderLoader;
    }

    private final OpenShiftWaiters waiters;

    public OpenShift(OpenShiftConfig openShiftConfig) {
        this.delegate = new KubernetesClientBuilder().withConfig(openShiftConfig).build()
                .adapt(NamespacedOpenShiftClient.class);
        this.waiters = new OpenShiftWaiters(this);
    }

    public void setupPullSecret(String secret) {
        setupPullSecret("xtf-pull-secret", secret);
    }

    /**
     * Convenient method to create pull secret for authenticated image registries.
     * The secret content must be provided in "dockerconfigjson" formar.
     *
     * E.g.: {@code {"auths":{"registry.redhat.io":{"auth":"<REDACTED_TOKEN>"}}}}
     *
     * Linking Secret to ServiceAccount is based on OpenShift documentation:
     * https://docs.openshift.com/container-platform/4.2/openshift_images/managing-images/using-image-pull-secrets.html
     *
     * @param name of the Secret to be created
     * @param secret content of Secret in json format
     */
    public void setupPullSecret(String name, String secret) {
        Secret pullSecret = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .addToLabels(KEEP_LABEL, "true")
                .endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .withData(Collections.singletonMap(".dockerconfigjson", Base64.getEncoder().encodeToString(secret.getBytes())))
                .build();
        secrets().resource(pullSecret).createOrReplace();
        serviceAccounts().withName("default").edit(new Visitor<ServiceAccountBuilder>() {
            @Override
            public void visit(ServiceAccountBuilder builder) {
                builder.addToImagePullSecrets(
                        new LocalObjectReferenceBuilder().withName(pullSecret.getMetadata().getName()).build());
            }
        });

        serviceAccounts().withName("builder").edit(new Visitor<ServiceAccountBuilder>() {
            @Override
            public void visit(ServiceAccountBuilder builder) {
                builder.addToSecrets(new ObjectReferenceBuilder().withName(pullSecret.getMetadata().getName()).build());
            }
        });
    }

    // General functions
    public KubernetesList createResources(HasMetadata... resources) {
        return createResources(Arrays.asList(resources));
    }

    public KubernetesList createResources(List<HasMetadata> resources) {
        KubernetesList list = new KubernetesList();
        list.setItems(resources);
        return createResources(list);
    }

    public KubernetesList createResources(KubernetesList resources) {
        KubernetesList list = new KubernetesList();
        list.setItems(resourceList(resources.getItems()).create());
        return list;
    }

    public boolean deleteResources(KubernetesList resources) {
        return resources.getItems().size() == resourceList(resources.getItems()).delete().size();
    }

    public void loadResource(InputStream is) {
        load(is).delete();
        load(is).create();
    }

    // Projects
    public ProjectRequest createProjectRequest() {
        return createProjectRequest(
                new ProjectRequestBuilder().withNewMetadata().withName(getNamespace()).endMetadata().build());
    }

    public ProjectRequest createProjectRequest(String name) {
        return createProjectRequest(new ProjectRequestBuilder().withNewMetadata().withName(name).endMetadata().build());
    }

    public ProjectRequest createProjectRequest(ProjectRequest projectRequest) {
        return projectrequests().create(projectRequest);
    }

    /**
     * Calls recreateProject(namespace).
     *
     * @return project request
     * @see OpenShift#recreateProject(String)
     */
    public ProjectRequest recreateProject() {
        return recreateProject(new ProjectRequestBuilder().withNewMetadata().withName(getNamespace()).endMetadata().build());
    }

    /**
     * Creates or recreates project specified by name.
     *
     * @param name name of a project to be created
     * @return ProjectRequest instance
     */
    public ProjectRequest recreateProject(String name) {
        return recreateProject(new ProjectRequestBuilder().withNewMetadata().withName(name).endMetadata().build());
    }

    /**
     * Creates or recreates project specified by projectRequest instance.
     * 
     * @param projectRequest project request instance
     * @return ProjectRequest instance
     */
    public ProjectRequest recreateProject(ProjectRequest projectRequest) {
        boolean deleted = deleteProject(projectRequest.getMetadata().getName());
        if (deleted) {
            BooleanSupplier bs = () -> getProject(projectRequest.getMetadata().getName()) == null;
            new SimpleWaiter(bs, TimeUnit.MILLISECONDS, WaitingConfig.timeout(),
                    "Waiting for old project deletion before creating new one").waitFor();
        }
        return createProjectRequest(projectRequest);
    }

    /**
     * Tries to retrieve project with name 'name'. Swallows KubernetesClientException
     * if project doesn't exist or isn't accessible for user.
     *
     * @param name name of requested project.
     * @return Project instance if accessible otherwise null.
     */
    public Project getProject(String name) {
        try {
            return projects().withName(name).get();
        } catch (KubernetesClientException e) {
            return null;
        }
    }

    public Project getProject() {
        try {
            return projects().withName(getNamespace()).get();
        } catch (KubernetesClientException e) {
            return null;
        }
    }

    public boolean deleteProject() {
        return deleteProject(getNamespace());
    }

    public boolean deleteProject(String name) {
        return getProject(name) != null ? !projects().withName(name).delete().isEmpty() : false;
    }

    // ImageStreams
    public ImageStream createImageStream(ImageStream imageStream) {
        return imageStreams().resource(imageStream).create();
    }

    public ImageStream getImageStream(String name) {
        return imageStreams().withName(name).get();
    }

    public List<ImageStream> getImageStreams() {
        return imageStreams().list().getItems();
    }

    // StatefulSets
    public StatefulSet createStatefulSet(StatefulSet statefulSet) {
        return apps().statefulSets().resource(statefulSet).create();
    }

    public StatefulSet getStatefulSet(String name) {
        return apps().statefulSets().withName(name).get();
    }

    public List<StatefulSet> getStatefulSets() {
        return apps().statefulSets().list().getItems();
    }

    public boolean deleteImageStream(ImageStream imageStream) {
        return !imageStreams().resource(imageStream).delete().isEmpty();
    }

    // ImageStreamsTags
    public ImageStreamTag createImageStreamTag(ImageStreamTag imageStreamTag) {
        return imageStreamTags().resource(imageStreamTag).create();
    }

    public ImageStreamTag getImageStreamTag(String imageStreamName, String tag) {
        return imageStreamTags().withName(imageStreamName + ":" + tag).get();
    }

    public List<ImageStreamTag> getImageStreamTags() {
        return imageStreamTags().list().getItems();
    }

    public boolean deleteImageStreamTag(ImageStreamTag imageStreamTag) {
        return !imageStreamTags().resource(imageStreamTag).delete().isEmpty();
    }

    // Pods
    public Pod createPod(Pod pod) {
        return pods().resource(pod).create();
    }

    public Pod getPod(String name) {
        return pods().withName(name).get();
    }

    public String getPodLog(String deploymentConfigName) {
        return getPodLog(this.getAnyPod(deploymentConfigName));
    }

    public String getPodLog(Pod pod) {
        return getPodLog(pod, getAnyContainer(pod));
    }

    public String getPodLog(Pod pod, String containerName) {
        return getPodLog(pod, getContainer(pod, containerName));
    }

    public String getPodLog(Pod pod, Container container) {
        if (Objects.nonNull(container)) {
            return pods().withName(pod.getMetadata().getName()).inContainer(container.getName()).getLog();
        } else {
            return pods().withName(pod.getMetadata().getName()).getLog();
        }
    }

    /**
     * Return logs of all containers from the pod
     *
     * @param pod Pod to retrieve from
     * @return Map of container name / logs
     */
    public Map<String, String> getAllContainerLogs(Pod pod) {
        return retrieveFromPodContainers(pod, container -> this.getPodLog(pod, container));
    }

    public Reader getPodLogReader(Pod pod) {
        return getPodLogReader(pod, getAnyContainer(pod));
    }

    public Reader getPodLogReader(Pod pod, String containerName) {
        return getPodLogReader(pod, getContainer(pod, containerName));
    }

    public Reader getPodLogReader(Pod pod, Container container) {
        if (Objects.nonNull(container)) {
            return pods().withName(pod.getMetadata().getName()).inContainer(container.getName()).getLogReader();
        } else {
            return pods().withName(pod.getMetadata().getName()).getLogReader();
        }
    }

    /**
     * Return readers on logs of all containers from the pod
     *
     * @param pod Pod to retrieve from
     * @return Map of container name / reader
     */
    public Map<String, Reader> getAllContainerLogReaders(Pod pod) {
        return retrieveFromPodContainers(pod, container -> this.getPodLogReader(pod, container));
    }

    public Observable<String> observePodLog(String dcName) {
        return observePodLog(getAnyPod(dcName));
    }

    public Observable<String> observePodLog(Pod pod) {
        return observePodLog(pod, getAnyContainer(pod));
    }

    public Observable<String> observePodLog(Pod pod, String containerName) {
        return observePodLog(pod, getContainer(pod, containerName));
    }

    public Observable<String> observePodLog(Pod pod, Container container) {
        LogWatch watcher;
        if (Objects.nonNull(container)) {
            watcher = pods().withName(pod.getMetadata().getName()).inContainer(container.getName()).watchLog();
        } else {
            watcher = pods().withName(pod.getMetadata().getName()).watchLog();
        }
        return StringObservable.byLine(StringObservable.from(new InputStreamReader(watcher.getOutput())));
    }

    /**
     * Return obervables on logs of all containers from the pod
     *
     * @param pod Pod to retrieve from
     * @return Map of container name / logs obervable
     */
    public Map<String, Observable<String>> observeAllContainerLogs(Pod pod) {
        return retrieveFromPodContainers(pod, container -> this.observePodLog(pod, container));
    }

    public List<Pod> getPods() {
        return pods().list().getItems();
    }

    /**
     * @param deploymentConfigName name of deploymentConfig
     * @return all active pods created by specified deploymentConfig
     */
    public List<Pod> getPods(String deploymentConfigName) {
        return getLabeledPods("deploymentconfig", deploymentConfigName);
    }

    /**
     * @param deploymentConfigName name of deploymentConfig
     * @param version deployment version to be retrieved
     * @return active pods created by deploymentConfig with specified version
     */
    public List<Pod> getPods(String deploymentConfigName, int version) {
        return getLabeledPods("deployment", deploymentConfigName + "-" + version);
    }

    public List<Pod> getLabeledPods(String key, String value) {
        return getLabeledPods(Collections.singletonMap(key, value));
    }

    public List<Pod> getLabeledPods(Map<String, String> labels) {
        return pods().withLabels(labels).list().getItems();
    }

    public Pod getAnyPod(String deploymentConfigName) {
        return getAnyPod("deploymentconfig", deploymentConfigName);
    }

    public Pod getAnyPod(String key, String value) {
        return getAnyPod(Collections.singletonMap(key, value));
    }

    public Pod getAnyPod(Map<String, String> labels) {
        List<Pod> pods = getLabeledPods(labels);
        return pods.get(new Random().nextInt(pods.size()));
    }

    public boolean deletePod(Pod pod) {
        return deletePod(pod, 0L);
    }

    public boolean deletePod(Pod pod, long gracePeriod) {
        return !pods().withName(pod.getMetadata().getName()).withGracePeriod(gracePeriod).delete().isEmpty();
    }

    /**
     * Deletes pods with specified label.
     *
     * @param key key of the label
     * @param value value of the label
     * @return True if any pod has been deleted
     */
    public boolean deletePods(String key, String value) {
        return !pods().withLabel(key, value).delete().isEmpty();
    }

    public boolean deletePods(Map<String, String> labels) {
        return !pods().withLabels(labels).delete().isEmpty();
    }

    /**
     * Retrieve pod containers
     *
     * @param pod pod to retrieve in
     * @return List of containers of empty list if none ...
     */
    public List<Container> getAllContainers(Pod pod) {
        return Optional.ofNullable(pod.getSpec()).map(PodSpec::getContainers).orElse(new ArrayList<>());
    }

    /**
     * Retrieve any container from the given pod
     *
     * @param pod Pod to retrieve from
     * @return One random container from the pod
     */
    public Container getAnyContainer(Pod pod) {
        List<Container> containers = getAllContainers(pod);
        return containers.get(new Random().nextInt(containers.size()));
    }

    public Container getContainer(Pod pod, String containerName) {
        return getAllContainers(pod).stream()
                .filter(c -> c.getName().equals(containerName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Cannot find container with name " + containerName + " in pod " + pod.getMetadata().getName()));
    }

    private <R> Map<String, R> retrieveFromPodContainers(Pod pod, Function<Container, R> containerRetriever) {
        return getAllContainers(pod).stream().collect(Collectors.toMap(Container::getName, containerRetriever));
    }

    // Secrets
    public Secret createSecret(Secret secret) {
        return secrets().resource(secret).create();
    }

    public Secret getSecret(String name) {
        return secrets().withName(name).get();
    }

    public List<Secret> getSecrets() {
        return secrets().list().getItems();
    }

    /**
     * Retrieves secrets that aren't considered default. Secrets that are left out contain type starting with 'kubernetes.io/'.
     *
     * @return List of secrets that aren't considered default.
     */
    public List<Secret> getUserSecrets() {
        return secrets().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems().stream()
                .filter(s -> !s.getType().startsWith("kubernetes.io/"))
                .collect(Collectors.toList());
    }

    public boolean deleteSecret(Secret secret) {
        return !secrets().resource(secret).delete().isEmpty();
    }

    // Services
    public Service createService(Service service) {
        return services().resource(service).create();
    }

    public Service getService(String name) {
        return services().withName(name).get();
    }

    public List<Service> getServices() {
        return services().list().getItems();
    }

    public boolean deleteService(Service service) {
        return !services().resource(service).delete().isEmpty();
    }

    // Endpoints
    public Endpoints createEndpoint(Endpoints endpoint) {
        return endpoints().resource(endpoint).create();
    }

    public Endpoints getEndpoint(String name) {
        return endpoints().withName(name).get();
    }

    public List<Endpoints> getEndpoints() {
        return endpoints().list().getItems();
    }

    public boolean deleteEndpoint(Endpoints endpoint) {
        return !endpoints().resource(endpoint).delete().isEmpty();
    }

    // Routes
    public Route createRoute(Route route) {
        return routes().resource(route).create();
    }

    public Route getRoute(String name) {
        return routes().withName(name).get();
    }

    public List<Route> getRoutes() {
        return routes().list().getItems();
    }

    public boolean deleteRoute(Route route) {
        return !routes().resource(route).delete().isEmpty();
    }

    /**
     * Generates hostname as is expected to be generated by OpenShift instance.
     *
     * @param routeName prefix for route hostname
     * @return Hostname as is expected to be generated by OpenShift
     */
    public String generateHostname(String routeName) {
        if (routeSuffix == null) {
            synchronized (OpenShift.class) {
                if (routeSuffix == null) {
                    if (StringUtils.isNotBlank(routeDomain())) {
                        routeSuffix = routeDomain();
                    } else {
                        routeSuffix = retrieveRouteSuffix();
                    }
                }
            }
        }
        log.info("generateHostname returns namespace: " + routeName + "-" + getNamespace() + "." + routeSuffix);
        return routeName + "-" + getNamespace() + "." + routeSuffix;
    }

    private String retrieveRouteSuffix() {
        Route route = new Route();
        route.setMetadata(new ObjectMetaBuilder().withName("probing-route").build());
        route.setSpec(new RouteSpecBuilder().withNewTo().withKind("Service").withName("imaginary-service").endTo().build());

        route = createRoute(route);
        deleteRoute(route);

        return route.getSpec().getHost().replaceAll("^[^\\.]*\\.(.*)", "$1");
    }

    // ReplicationControllers - Only for internal usage with clean
    private List<ReplicationController> getReplicationControllers() {
        return replicationControllers().list().getItems();
    }

    private boolean deleteReplicationController(ReplicationController replicationController) {
        return !replicationControllers().withName(replicationController.getMetadata().getName()).cascading(false).delete()
                .isEmpty();
    }

    // DeploymentConfigs
    public DeploymentConfig createDeploymentConfig(DeploymentConfig deploymentConfig) {
        return deploymentConfigs().resource(deploymentConfig).create();
    }

    public DeploymentConfig getDeploymentConfig(String name) {
        return deploymentConfigs().withName(name).get();
    }

    public List<DeploymentConfig> getDeploymentConfigs() {
        return deploymentConfigs().list().getItems();
    }

    /**
     * Returns first container environment variables.
     *
     * @param name name of deploymentConfig
     * @return Map of environment variables
     */
    public Map<String, String> getDeploymentConfigEnvVars(String name) {
        Map<String, String> envVars = new HashMap<>();
        getDeploymentConfig(name).getSpec().getTemplate().getSpec().getContainers().get(0).getEnv()
                .forEach(envVar -> envVars.put(envVar.getName(), envVar.getValue()));
        return envVars;
    }

    public DeploymentConfig updateDeploymentconfig(DeploymentConfig deploymentConfig) {
        return deploymentConfigs().withName(deploymentConfig.getMetadata().getName()).replace();
    }

    /**
     * Updates deployment config environment variables with envVars values.
     *
     * @param name name of deploymentConfig
     * @param envVars environment variables
     *
     * @return deployment config
     */
    public DeploymentConfig updateDeploymentConfigEnvVars(String name, Map<String, String> envVars) {
        DeploymentConfig dc = getDeploymentConfig(name);

        List<EnvVar> vars = envVars.entrySet().stream()
                .map(x -> new EnvVarBuilder().withName(x.getKey()).withValue(x.getValue()).build())
                .collect(Collectors.toList());
        dc.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().removeIf(x -> envVars.containsKey(x.getName()));
        dc.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().addAll(vars);

        return updateDeploymentconfig(dc);
    }

    public boolean deleteDeploymentConfig(DeploymentConfig deploymentConfig) {
        return deleteDeploymentConfig(deploymentConfig, false);
    }

    public boolean deleteDeploymentConfig(DeploymentConfig deploymentConfig, boolean cascading) {
        return !deploymentConfigs().withName(deploymentConfig.getMetadata().getName()).cascading(cascading).delete().isEmpty();
    }

    /**
     * Scales deployment config to specified number of replicas.
     *
     * @param name name of deploymentConfig
     * @param replicas number of target replicas
     */
    public void scale(String name, int replicas) {
        deploymentConfigs().withName(name).scale(replicas);
    }

    /**
     * Redeploys deployment config to latest version.
     *
     * @param name name of deploymentConfig
     */
    public void deployLatest(String name) {
        deploymentConfigs().withName(name).deployLatest();
    }

    // Builds
    public Build getBuild(String name) {
        return inNamespace(getNamespace()).builds().withName(name).get();
    }

    public Build getLatestBuild(String buildConfigName) {
        long lastVersion = buildConfigs().withName(buildConfigName).get().getStatus().getLastVersion();
        return getBuild(buildConfigName + "-" + lastVersion);
    }

    public List<Build> getBuilds() {
        return builds().list().getItems();
    }

    public String getBuildLog(Build build) {
        return builds().withName(build.getMetadata().getName()).getLog();
    }

    public Reader getBuildLogReader(Build build) {
        return builds().withName(build.getMetadata().getName()).getLogReader();
    }

    public boolean deleteBuild(Build build) {
        return !builds().resource(build).delete().isEmpty();
    }

    public Build startBuild(String buildConfigName) {
        BuildRequest request = new BuildRequestBuilder().withNewMetadata().withName(buildConfigName).endMetadata().build();
        return buildConfigs().withName(buildConfigName).instantiate(request);
    }

    public Build startBinaryBuild(String buildConfigName, File file) {
        return buildConfigs().withName(buildConfigName).instantiateBinary().fromFile(file);
    }

    // BuildConfigs
    public BuildConfig createBuildConfig(BuildConfig buildConfig) {
        return buildConfigs().resource(buildConfig).create();
    }

    public BuildConfig getBuildConfig(String name) {
        return buildConfigs().withName(name).get();
    }

    public List<BuildConfig> getBuildConfigs() {
        return buildConfigs().list().getItems();
    }

    /**
     * Returns environment variables of buildConfig specified under sourceStrategy.
     *
     * @param name name of buildConfig
     * @return environment variables
     */
    public Map<String, String> getBuildConfigEnvVars(String name) {
        return getBuildConfig(name).getSpec().getStrategy().getSourceStrategy().getEnv().stream()
                .collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
    }

    public BuildConfig updateBuildConfig(BuildConfig buildConfig) {
        return buildConfigs().withName(buildConfig.getMetadata().getName()).replace();
    }

    /**
     * Updates build config with specified environment variables.
     *
     * @param name name of buildConfig
     * @param envVars environment variables
     *
     * @return build config
     */
    public BuildConfig updateBuildConfigEnvVars(String name, Map<String, String> envVars) {
        BuildConfig bc = getBuildConfig(name);

        List<EnvVar> vars = envVars.entrySet().stream()
                .map(x -> new EnvVarBuilder().withName(x.getKey()).withValue(x.getValue()).build())
                .collect(Collectors.toList());
        bc.getSpec().getStrategy().getSourceStrategy().getEnv().removeIf(x -> envVars.containsKey(x.getName()));
        bc.getSpec().getStrategy().getSourceStrategy().getEnv().addAll(vars);

        return updateBuildConfig(bc);
    }

    public boolean deleteBuildConfig(BuildConfig buildConfig) {
        return !buildConfigs().resource(buildConfig).delete().isEmpty();
    }

    // ServiceAccounts
    public ServiceAccount createServiceAccount(ServiceAccount serviceAccount) {
        return serviceAccounts().resource(serviceAccount).create();
    }

    public ServiceAccount getServiceAccount(String name) {
        return serviceAccounts().withName(name).get();
    }

    public List<ServiceAccount> getServiceAccounts() {
        return serviceAccounts().list().getItems();
    }

    /**
     * Retrieves service accounts that aren't considered default.
     * Service accounts that are left out from list:
     * <ul>
     * <li>builder</li>
     * <li>default</li>
     * <li>deployer</li>
     * </ul>
     *
     * @return List of service accounts that aren't considered default.
     */
    public List<ServiceAccount> getUserServiceAccounts() {
        return serviceAccounts().withLabelNotIn(KEEP_LABEL, "", "true").list().getItems().stream()
                .filter(sa -> !sa.getMetadata().getName().matches("builder|default|deployer"))
                .collect(Collectors.toList());
    }

    public boolean deleteServiceAccount(ServiceAccount serviceAccount) {
        return !serviceAccounts().resource(serviceAccount).delete().isEmpty();
    }

    // RoleBindings
    public RoleBinding createRoleBinding(RoleBinding roleBinding) {
        return rbac().roleBindings().resource(roleBinding).create();
    }

    public RoleBinding getRoleBinding(String name) {
        return rbac().roleBindings().withName(name).get();
    }

    public List<RoleBinding> getRoleBindings() {
        return rbac().roleBindings().list().getItems();
    }

    public Role getRole(String name) {
        return rbac().roles().withName(name).get();
    }

    public List<Role> getRoles() {
        return rbac().roles().list().getItems();
    }

    /**
     * Retrieves role bindings that aren't considered default.
     * Role bindings that are left out from list:
     * <ul>
     * <li>admin</li>
     * <li>system:deployers</li>
     * <li>system:image-builders</li>
     * <li>system:image-pullers</li>
     * </ul>
     *
     * @return List of role bindings that aren't considered default.
     */
    public List<RoleBinding> getUserRoleBindings() {
        return rbac().roleBindings().withLabelNotIn(KEEP_LABEL, "", "true")
                .withLabelNotIn("olm.owner.kind", "ClusterServiceVersion").list().getItems().stream()
                .filter(rb -> !rb.getMetadata().getName()
                        .matches("admin|system:deployers|system:image-builders|system:image-pullers"))
                .collect(Collectors.toList());
    }

    public boolean deleteRoleBinding(RoleBinding roleBinding) {
        return !rbac().roleBindings().resource(roleBinding).delete().isEmpty();
    }

    public RoleBinding addRoleToUser(String roleName, String username) {
        return addRoleToUser(roleName, null, username);
    }

    public RoleBinding addRoleToUser(String roleName, String roleKind, String username) {
        RoleBinding roleBinding = getOrCreateRoleBinding(roleName);
        addSubjectToRoleBinding(roleBinding, "User", username, null);
        return updateRoleBinding(roleBinding);
    }

    public RoleBinding addRoleToServiceAccount(String roleName, String serviceAccountName) {
        return addRoleToServiceAccount(roleName, serviceAccountName, getNamespace());
    }

    public RoleBinding addRoleToServiceAccount(String roleName, String serviceAccountName, String namespace) {
        return addRoleToServiceAccount(roleName, null, serviceAccountName, namespace);
    }

    public RoleBinding addRoleToServiceAccount(String roleName, String roleKind, String serviceAccountName, String namespace) {
        RoleBinding roleBinding = getOrCreateRoleBinding(roleName, roleKind);
        addSubjectToRoleBinding(roleBinding, "ServiceAccount", serviceAccountName, namespace);
        return updateRoleBinding(roleBinding);
    }

    /**
     * Most of the groups are `system:*` wide therefore use `kind: ClusterRole`
     *
     * @param roleName role name
     * @param groupName group name
     * @return role binging
     * @deprecated use method {@link #addRoleToGroup(String, String, String)}
     *
     */
    @Deprecated
    public RoleBinding addRoleToGroup(String roleName, String groupName) {
        RoleBinding roleBinding = getOrCreateRoleBinding(roleName);
        addSubjectToRoleBinding(roleBinding, "Group", groupName, null);
        return updateRoleBinding(roleBinding);
    }

    public RoleBinding addRoleToGroup(String roleName, String roleKind, String groupName) {
        RoleBinding roleBinding = getOrCreateRoleBinding(roleName, roleKind);
        addSubjectToRoleBinding(roleBinding, "Group", groupName, null);
        return updateRoleBinding(roleBinding);
    }

    private RoleBinding getOrCreateRoleBinding(String name) {
        return getOrCreateRoleBinding(name, null);
    }

    private RoleBinding getOrCreateRoleBinding(String name, String kind) {
        RoleBinding roleBinding = getRoleBinding(name);
        if (roleBinding == null) {
            // {admin, edit, view} are K8s ClusterRole that are considered user-facing
            if (kind == null) {
                kind = "Role";
                if (Arrays.asList("admin", "edit", "view").contains(name)) {
                    kind = "ClusterRole";
                }
            }
            roleBinding = new RoleBindingBuilder()
                    .withNewMetadata().withName(name).endMetadata()
                    .withNewRoleRef().withKind(kind).withName(name).endRoleRef()
                    .build();
            createRoleBinding(roleBinding);
        }
        return roleBinding;
    }

    public RoleBinding updateRoleBinding(RoleBinding roleBinding) {
        return rbac().roleBindings().withName(roleBinding.getMetadata().getName()).replace(roleBinding);
    }

    private void addSubjectToRoleBinding(RoleBinding roleBinding, String entityKind, String entityName) {
        addSubjectToRoleBinding(roleBinding, entityKind, entityName, null);
    }

    private void addSubjectToRoleBinding(RoleBinding roleBinding, String entityKind, String entityName,
            String entityNamespace) {
        SubjectBuilder subjectBuilder = new SubjectBuilder().withKind(entityKind).withName(entityName);
        if (entityNamespace != null) {
            subjectBuilder.withNamespace(entityNamespace);
        }
        Subject subject = subjectBuilder.build();
        if (roleBinding.getSubjects().stream()
                .noneMatch(x -> x.getName().equals(subject.getName()) && x.getKind().equals(subject.getKind()))) {
            roleBinding.getSubjects().add(subject);
        }
    }

    public RoleBinding removeRoleFromServiceAccount(String roleName, String serviceAccountName) {
        return removeRoleFromEntity(roleName, "ServiceAccount", serviceAccountName);
    }

    public RoleBinding removeRoleFromEntity(String roleName, String entityKind, String entityName) {
        RoleBinding roleBinding = this.rbac().roleBindings().withName(roleName).get();

        if (roleBinding != null) {
            roleBinding.getSubjects().removeIf(s -> s.getName().equals(entityName) && s.getKind().equals(entityKind));
            return updateRoleBinding(roleBinding);
        }
        return null;
    }

    // ResourceQuotas
    public ResourceQuota createResourceQuota(ResourceQuota resourceQuota) {
        return resourceQuotas().resource(resourceQuota).create();
    }

    public ResourceQuota getResourceQuota(String name) {
        return resourceQuotas().withName(name).get();
    }

    public boolean deleteResourceQuota(ResourceQuota resourceQuota) {
        return !resourceQuotas().resource(resourceQuota).delete().isEmpty();
    }

    // Persistent volume claims
    public PersistentVolumeClaim createPersistentVolumeClaim(PersistentVolumeClaim pvc) {
        return persistentVolumeClaims().resource(pvc).create();
    }

    public PersistentVolumeClaim getPersistentVolumeClaim(String name) {
        return persistentVolumeClaims().withName(name).get();
    }

    public List<PersistentVolumeClaim> getPersistentVolumeClaims() {
        return persistentVolumeClaims().list().getItems();
    }

    public boolean deletePersistentVolumeClaim(PersistentVolumeClaim pvc) {
        return !persistentVolumeClaims().resource(pvc).delete().isEmpty();
    }

    // HorizontalPodAutoscalers
    public HorizontalPodAutoscaler createHorizontalPodAutoscaler(HorizontalPodAutoscaler hpa) {
        return autoscaling().v1().horizontalPodAutoscalers().resource(hpa).create();
    }

    public HorizontalPodAutoscaler getHorizontalPodAutoscaler(String name) {
        return autoscaling().v1().horizontalPodAutoscalers().withName(name).get();
    }

    public List<HorizontalPodAutoscaler> getHorizontalPodAutoscalers() {
        return autoscaling().v1().horizontalPodAutoscalers().list().getItems();
    }

    public boolean deleteHorizontalPodAutoscaler(HorizontalPodAutoscaler hpa) {
        return !autoscaling().v1().horizontalPodAutoscalers().resource(hpa).delete().isEmpty();
    }

    // ConfigMaps
    public ConfigMap createConfigMap(ConfigMap configMap) {
        return configMaps().resource(configMap).create();
    }

    public ConfigMap getConfigMap(String name) {
        return configMaps().withName(name).get();
    }

    public List<ConfigMap> getConfigMaps() {
        return configMaps().list().getItems();
    }

    /**
     * Retrieves all configmaps but "kube-root-ca.crt" and "openshift-service-ca.crt" which are created out of the box.
     *
     * @return List of configmaps created by user
     */
    public List<ConfigMap> getUserConfigMaps() {
        return configMaps().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems().stream()
                .filter(cm -> !cm.getMetadata().getName().equals("kube-root-ca.crt"))
                .filter(cm -> !cm.getMetadata().getName().equals("openshift-service-ca.crt"))
                .collect(Collectors.toList());
    }

    public boolean deleteConfigMap(ConfigMap configMap) {
        return !configMaps().resource(configMap).delete().isEmpty();
    }

    public Template createTemplate(Template template) {
        return templates().resource(template).create();
    }

    public Template getTemplate(String name) {
        return templates().withName(name).get();
    }

    public List<Template> getTemplates() {
        return templates().list().getItems();
    }

    public boolean deleteTemplate(String name) {
        return !templates().withName(name).delete().isEmpty();
    }

    public boolean deleteTemplate(Template template) {
        return !templates().resource(template).delete().isEmpty();
    }

    public Template loadAndCreateTemplate(InputStream is) {
        Template t = templates().load(is).get();
        deleteTemplate(t);

        return createTemplate(t);
    }

    public KubernetesList recreateAndProcessTemplate(Template template, Map<String, String> parameters) {
        deleteTemplate(template.getMetadata().getName());
        createTemplate(template);

        return processTemplate(template.getMetadata().getName(), parameters);
    }

    public KubernetesList recreateAndProcessAndDeployTemplate(Template template, Map<String, String> parameters) {
        return createResources(recreateAndProcessTemplate(template, parameters));
    }

    public KubernetesList processTemplate(String name, Map<String, String> parameters) {
        ParameterValue[] values = processParameters(parameters);
        return templates().withName(name).process(values);
    }

    public KubernetesList processAndDeployTemplate(String name, Map<String, String> parameters) {
        return createResources(processTemplate(name, parameters));
    }

    private ParameterValue[] processParameters(Map<String, String> parameters) {
        return parameters.entrySet().stream().map(entry -> new ParameterValue(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList()).toArray(new ParameterValue[parameters.size()]);
    }

    // Nodes
    public Node getNode(String name) {
        return nodes().withName(name).get();
    }

    public List<Node> getNodes() {
        return nodes().list().getItems();
    }

    public List<Node> getNodes(Map<String, String> labels) {
        return nodes().withLabels(labels).list().getItems();
    }

    // Events
    public EventList getEventList() {
        return new EventList(v1().events().list().getItems());
    }

    /**
     * Use {@link OpenShift#getEventList()} instead
     *
     * @return list of events
     */
    @Deprecated
    public List<Event> getEvents() {
        return v1().events().list().getItems();
    }

    // Port Forward
    public LocalPortForward portForward(String deploymentConfigName, int remotePort, int localPort) {
        return portForward(getAnyPod(deploymentConfigName), remotePort, localPort);
    }

    public LocalPortForward portForward(Pod pod, int remotePort, int localPort) {
        return pods().withName(pod.getMetadata().getName()).portForward(remotePort, localPort);
    }

    // PodShell
    public PodShell podShell(String dcName) {
        return podShell(getAnyPod(dcName));
    }

    public PodShell podShell(Pod pod) {
        return new PodShell(this, pod);
    }

    public PodShell podShell(Pod pod, String containerName) {
        return new PodShell(this, pod, containerName);
    }

    // Clean up function
    /**
     * <pre>
     * Deletes all* resources in namespace. Doesn't wait till all are deleted.
     *
     * * Only user created configmaps, secrets, service accounts and role bindings are deleted. Default will remain.
     *
     * </pre>
     *
     * @return waiter (for cleaning all resources)
     * @see #getUserConfigMaps()
     * @see #getUserSecrets()
     * @see #getUserServiceAccounts()
     * @see #getUserRoleBindings()
     */
    public Waiter clean() {
        for (CustomResourceDefinitionContextProvider crdContextProvider : OpenShift.getCRDContextProviders()) {
            try {
                newHasMetadataOperation(crdContextProvider.getContext(), GenericKubernetesResource.class,
                        GenericKubernetesResourceList.class)
                                .inNamespace(getNamespace()).delete();
                log.debug("DELETE :: " + crdContextProvider.getContext().getName() + " instances");
            } catch (KubernetesClientException kce) {
                log.debug(crdContextProvider.getContext().getName() + " might not be installed on the cluster.", kce);
            }
        }

        templates().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        apps().deployments().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        apps().replicaSets().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        apps().statefulSets().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        batch().v1().jobs().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        deploymentConfigs().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        replicationControllers().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        buildConfigs().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        imageStreams().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        endpoints().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        services().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        builds().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        routes().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        pods().withLabelNotIn(KEEP_LABEL, "", "true").withGracePeriod(0).delete();
        persistentVolumeClaims().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        autoscaling().v1().horizontalPodAutoscalers().withLabelNotIn(KEEP_LABEL, "", "true").delete();
        getUserConfigMaps().forEach(this::deleteConfigMap);
        getUserSecrets().forEach(this::deleteSecret);
        getUserServiceAccounts().forEach((sa) -> {
            this.deleteServiceAccount(sa);
        });
        getUserRoleBindings().forEach(this::deleteRoleBinding);
        rbac().roles().withLabelNotIn(KEEP_LABEL, "", "true").withLabelNotIn("olm.owner.kind", "ClusterServiceVersion")
                .delete();

        for (HasMetadata hasMetadata : listRemovableResources()) {
            log.warn("DELETE LEFTOVER :: " + hasMetadata.getKind() + "/" + hasMetadata.getMetadata().getName());
            resource(hasMetadata).withGracePeriod(0).withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
        }

        return waiters.isProjectClean();
    }

    List<HasMetadata> listRemovableResources() {
        // keep the order for deletion to prevent K8s creating resources again
        List<HasMetadata> removables = new ArrayList<>();
        removables.addAll(templates().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(apps().deployments().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(apps().replicaSets().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(batch().v1().jobs().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(deploymentConfigs().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(apps().statefulSets().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(replicationControllers().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(buildConfigs().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(imageStreams().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(endpoints().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(services().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(builds().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(routes().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(pods().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(persistentVolumeClaims().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list().getItems());
        removables.addAll(autoscaling().v1().horizontalPodAutoscalers().withLabelNotIn(OpenShift.KEEP_LABEL, "", "true").list()
                .getItems());
        removables.addAll(getUserConfigMaps());
        removables.addAll(getUserSecrets());
        removables.addAll(getUserServiceAccounts());
        removables.addAll(getUserRoleBindings());
        removables.addAll(rbac().roles().withLabelNotIn(KEEP_LABEL, "", "true")
                .withLabelNotIn("olm.owner.kind", "ClusterServiceVersion").list().getItems());

        return removables;
    }

    // Logs storing
    public Path storePodLog(Pod pod, Path dirPath, String fileName) throws IOException {
        String log = getPodLog(pod);
        return storeLog(log, dirPath, fileName);
    }

    public Path storeBuildLog(Build build, Path dirPath, String fileName) throws IOException {
        String log = getBuildLog(build);
        return storeLog(log, dirPath, fileName);
    }

    private Path storeLog(String log, Path dirPath, String fileName) throws IOException {
        Path filePath = dirPath.resolve(fileName);

        Files.createDirectories(dirPath);
        Files.createFile(filePath);
        Files.write(filePath, log.getBytes());

        return filePath;
    }

    // Waiting

    /**
     * Use {@link OpenShiftWaiters#get(OpenShift, cz.xtf.core.waiting.failfast.FailFastCheck)} instead.
     *
     * @return standard openshift waiters (with default fail fast checks)
     */
    @Deprecated
    public OpenShiftWaiters waiters() {
        return waiters;
    }

    private static boolean isEqualOpenshiftConfig(OpenShiftConfig newConfig, OpenShiftConfig existingConfig) {
        return new EqualsBuilder()
                .append(newConfig.getOpenShiftUrl(), existingConfig.getOpenShiftUrl())
                .append(newConfig.getNamespace(), existingConfig.getNamespace())
                .append(newConfig.getUsername(), existingConfig.getUsername())
                .append(newConfig.getPassword(), existingConfig.getPassword())
                //.append(newConfig.getRequestConfig().getOauthToken(), existingConfig.getRequestConfig().getOauthToken())
                .append(newConfig.isTrustCerts(), existingConfig.isTrustCerts())
                .isEquals();
    }

    @Override
    public URL getOpenshiftUrl() {
        return delegate.getOpenshiftUrl();
    }

    @Override
    public OpenShiftConfigAPIGroupDSL config() {
        return delegate.config();
    }

    @Override
    public OpenShiftConsoleAPIGroupDSL console() {
        return delegate.console();
    }

    @Override
    public OpenShiftClusterAutoscalingAPIGroupDSL clusterAutoscaling() {
        return delegate.clusterAutoscaling();
    }

    @Override
    public OpenShiftHiveAPIGroupDSL hive() {
        return delegate.hive();
    }

    @Override
    public OpenShiftOperatorAPIGroupDSL operator() {
        return delegate.operator();
    }

    @Override
    public OpenShiftOperatorHubAPIGroupDSL operatorHub() {
        return delegate.operatorHub();
    }

    @Override
    public ApiextensionsAPIGroupDSL apiextensions() {
        return delegate.apiextensions();
    }

    @Override
    public NonNamespaceOperation<CertificateSigningRequest, CertificateSigningRequestList, Resource<CertificateSigningRequest>> certificateSigningRequests() {
        return delegate.certificateSigningRequests();
    }

    @Override
    public CertificatesAPIGroupDSL certificates() {
        return delegate.certificates();
    }

    @Override
    public MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> genericKubernetesResources(
            ResourceDefinitionContext context) {
        return delegate.genericKubernetesResources(context);
    }

    @Override
    public MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> genericKubernetesResources(
            String apiVersion, String kind) {
        return delegate.genericKubernetesResources(apiVersion, kind);
    }

    @Override
    public DiscoveryAPIGroupDSL discovery() {
        return delegate.discovery();
    }

    @Override
    public DynamicResourceAllocationAPIGroupDSL dynamicResourceAllocation() {
        return delegate.dynamicResourceAllocation();
    }

    @Override
    public EventingAPIGroupDSL events() {
        return delegate.events();
    }

    @Override
    public ExtensionsAPIGroupDSL extensions() {
        return delegate.extensions();
    }

    @Override
    public FlowControlAPIGroupDSL flowControl() {
        return delegate.flowControl();
    }

    @Override
    public VersionInfo getVersion() {
        return delegate.getVersion();
    }

    @Override
    public VersionInfo getKubernetesVersion() {
        return delegate.getKubernetesVersion();
    }

    @Override
    public AdmissionRegistrationAPIGroupDSL admissionRegistration() {
        return delegate.admissionRegistration();
    }

    @Override
    public VersionInfo getOpenShiftV3Version() {
        return delegate.getOpenShiftV3Version();
    }

    @Override
    public String getOpenShiftV4Version() {
        return delegate.getOpenShiftV4Version();
    }

    @Override
    public AppsAPIGroupDSL apps() {
        return delegate.apps();
    }

    @Override
    public AutoscalingAPIGroupDSL autoscaling() {
        return delegate.autoscaling();
    }

    @Override
    public MachineConfigurationAPIGroupDSL machineConfigurations() {
        return delegate.machineConfigurations();
    }

    @Override
    public OpenShiftMachineAPIGroupDSL machine() {
        return delegate.machine();
    }

    @Override
    public OpenShiftMonitoringAPIGroupDSL monitoring() {
        return delegate.monitoring();
    }

    @Override
    public NonNamespaceOperation<NetNamespace, NetNamespaceList, Resource<NetNamespace>> netNamespaces() {
        return delegate.netNamespaces();
    }

    @Override
    public NonNamespaceOperation<ClusterNetwork, ClusterNetworkList, Resource<ClusterNetwork>> clusterNetworks() {
        return delegate.clusterNetworks();
    }

    @Override
    public MixedOperation<EgressNetworkPolicy, EgressNetworkPolicyList, Resource<EgressNetworkPolicy>> egressNetworkPolicies() {
        return delegate.egressNetworkPolicies();
    }

    @Override
    public NonNamespaceOperation<HostSubnet, HostSubnetList, Resource<HostSubnet>> hostSubnets() {
        return delegate.hostSubnets();
    }

    @Override
    public NetworkAPIGroupDSL network() {
        return delegate.network();
    }

    @Override
    public StorageAPIGroupDSL storage() {
        return delegate.storage();
    }

    @Override
    public BatchAPIGroupDSL batch() {
        return delegate.batch();
    }

    @Override
    public MetricAPIGroupDSL top() {
        return delegate.top();
    }

    @Override
    public PolicyAPIGroupDSL policy() {
        return delegate.policy();
    }

    @Override
    public RbacAPIGroupDSL rbac() {
        return delegate.rbac();
    }

    @Override
    public SchedulingAPIGroupDSL scheduling() {
        return delegate.scheduling();
    }

    @Override
    public NonNamespaceOperation<ComponentStatus, ComponentStatusList, Resource<ComponentStatus>> componentstatuses() {
        return delegate.componentstatuses();
    }

    @Override
    public NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata> load(InputStream is) {
        return delegate.load(is);
    }

    @Override
    public NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata> resourceList(String s) {
        return delegate.resourceList(s);
    }

    @Override
    public NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata> resourceList(KubernetesResourceList list) {
        return delegate.resourceList(list);
    }

    @Override
    public NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata> resourceList(HasMetadata... items) {
        return delegate.resourceList(items);
    }

    @Override
    public NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata> resourceList(
            Collection<? extends HasMetadata> items) {
        return delegate.resourceList(items);
    }

    @Override
    public <T extends HasMetadata> NamespaceableResource<T> resource(T is) {
        return delegate.resource(is);
    }

    @Override
    public NamespaceableResource<HasMetadata> resource(String s) {
        return delegate.resource(s);
    }

    @Override
    public NamespaceableResource<HasMetadata> resource(InputStream is) {
        return delegate.resource(is);
    }

    @Override
    public MixedOperation<Binding, KubernetesResourceList<Binding>, Resource<Binding>> bindings() {
        return delegate.bindings();
    }

    @Override
    public MixedOperation<Endpoints, EndpointsList, Resource<Endpoints>> endpoints() {
        return delegate.endpoints();
    }

    @Override
    public NonNamespaceOperation<Namespace, NamespaceList, Resource<Namespace>> namespaces() {
        return delegate.namespaces();
    }

    @Override
    public NonNamespaceOperation<Node, NodeList, Resource<Node>> nodes() {
        return delegate.nodes();
    }

    @Override
    public NonNamespaceOperation<PersistentVolume, PersistentVolumeList, Resource<PersistentVolume>> persistentVolumes() {
        return delegate.persistentVolumes();
    }

    @Override
    public MixedOperation<PersistentVolumeClaim, PersistentVolumeClaimList, Resource<PersistentVolumeClaim>> persistentVolumeClaims() {
        return delegate.persistentVolumeClaims();
    }

    @Override
    public MixedOperation<Pod, PodList, PodResource> pods() {
        return delegate.pods();
    }

    @Override
    public MixedOperation<ReplicationController, ReplicationControllerList, RollableScalableResource<ReplicationController>> replicationControllers() {
        return delegate.replicationControllers();
    }

    @Override
    public MixedOperation<ResourceQuota, ResourceQuotaList, Resource<ResourceQuota>> resourceQuotas() {
        return delegate.resourceQuotas();
    }

    @Override
    public MixedOperation<Secret, SecretList, Resource<Secret>> secrets() {
        return delegate.secrets();
    }

    @Override
    public MixedOperation<Service, ServiceList, ServiceResource<Service>> services() {
        return delegate.services();
    }

    @Override
    public MixedOperation<ServiceAccount, ServiceAccountList, Resource<ServiceAccount>> serviceAccounts() {
        return delegate.serviceAccounts();
    }

    @Override
    public NonNamespaceOperation<APIService, APIServiceList, Resource<APIService>> apiServices() {
        return delegate.apiServices();
    }

    @Override
    public MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps() {
        return delegate.configMaps();
    }

    @Override
    public MixedOperation<LimitRange, LimitRangeList, Resource<LimitRange>> limitRanges() {
        return delegate.limitRanges();
    }

    @Override
    public AuthorizationAPIGroupDSL authorization() {
        return delegate.authorization();
    }

    @Override
    public AuthenticationAPIGroupDSL authentication() {
        return delegate.authentication();
    }

    @Override
    public InOutCreateable<TokenReview, TokenReview> tokenReviews() {
        return delegate.tokenReviews();
    }

    @Override
    public SharedInformerFactory informers() {
        return delegate.informers();
    }

    @Override
    public LeaderElectorBuilder leaderElector() {
        return delegate.leaderElector();
    }

    @Override
    public MixedOperation<Lease, LeaseList, Resource<Lease>> leases() {
        return delegate.leases();
    }

    @Override
    public V1APIGroupDSL v1() {
        return delegate.v1();
    }

    @Override
    public RunOperations run() {
        return delegate.run();
    }

    @Override
    public NonNamespaceOperation<RuntimeClass, RuntimeClassList, Resource<RuntimeClass>> runtimeClasses() {
        return delegate.runtimeClasses();
    }

    @Override
    public void visitResources(ApiVisitor visitor) {
        delegate.visitResources(visitor);
    }

    @Override
    public KubernetesSerialization getKubernetesSerialization() {
        return delegate.getKubernetesSerialization();
    }

    @Override
    public NonNamespaceOperation<APIRequestCount, APIRequestCountList, Resource<APIRequestCount>> apiRequestCounts() {
        return delegate.apiRequestCounts();
    }

    @Override
    public MixedOperation<BareMetalHost, BareMetalHostList, Resource<BareMetalHost>> bareMetalHosts() {
        return delegate.bareMetalHosts();
    }

    @Override
    public MixedOperation<Build, BuildList, BuildResource> builds() {
        return delegate.builds();
    }

    @Override
    public MixedOperation<BuildConfig, BuildConfigList, BuildConfigResource<BuildConfig, Void, Build>> buildConfigs() {
        return delegate.buildConfigs();
    }

    @Override
    public MixedOperation<CredentialsRequest, CredentialsRequestList, Resource<CredentialsRequest>> credentialsRequests() {
        return delegate.credentialsRequests();
    }

    @Override
    public MixedOperation<DeploymentConfig, DeploymentConfigList, DeployableScalableResource<DeploymentConfig>> deploymentConfigs() {
        return delegate.deploymentConfigs();
    }

    @Override
    public NonNamespaceOperation<Group, GroupList, Resource<Group>> groups() {
        return delegate.groups();
    }

    @Override
    public NonNamespaceOperation<HelmChartRepository, HelmChartRepositoryList, Resource<HelmChartRepository>> helmChartRepositories() {
        return delegate.helmChartRepositories();
    }

    @Override
    public NonNamespaceOperation<Image, ImageList, Resource<Image>> images() {
        return delegate.images();
    }

    @Override
    public MixedOperation<ImageTag, ImageTagList, Resource<ImageTag>> imageTags() {
        return delegate.imageTags();
    }

    @Override
    public MixedOperation<ImageStream, ImageStreamList, Resource<ImageStream>> imageStreams() {
        return delegate.imageStreams();
    }

    @Override
    public MixedOperation<ImageStreamTag, ImageStreamTagList, Resource<ImageStreamTag>> imageStreamTags() {
        return delegate.imageStreamTags();
    }

    @Override
    public NamespacedInOutCreateable<ImageStreamImport, ImageStreamImport> imageStreamImports() {
        return delegate.imageStreamImports();
    }

    @Override
    public NamespacedInOutCreateable<ImageStreamMapping, ImageStreamMapping> imageStreamMappings() {
        return delegate.imageStreamMappings();
    }

    @Override
    public Namespaceable<Nameable<? extends Gettable<ImageStreamImage>>> imageStreamImages() {
        return delegate.imageStreamImages();
    }

    @Override
    public NameableCreateOrDeleteable imageSignatures() {
        return delegate.imageSignatures();
    }

    @Override
    public NonNamespaceOperation<io.fabric8.openshift.api.model.miscellaneous.imageregistry.operator.v1.Config, ConfigList, Resource<io.fabric8.openshift.api.model.miscellaneous.imageregistry.operator.v1.Config>> imageRegistryOperatorConfigs() {
        return delegate.imageRegistryOperatorConfigs();
    }

    @Override
    public MixedOperation<NetworkAttachmentDefinition, NetworkAttachmentDefinitionList, Resource<NetworkAttachmentDefinition>> networkAttachmentDefinitions() {
        return delegate.networkAttachmentDefinitions();
    }

    @Override
    public NonNamespaceOperation<OAuthAccessToken, OAuthAccessTokenList, Resource<OAuthAccessToken>> oAuthAccessTokens() {
        return delegate.oAuthAccessTokens();
    }

    @Override
    public NonNamespaceOperation<OAuthAuthorizeToken, OAuthAuthorizeTokenList, Resource<OAuthAuthorizeToken>> oAuthAuthorizeTokens() {
        return delegate.oAuthAuthorizeTokens();
    }

    @Override
    public NonNamespaceOperation<OAuthClient, OAuthClientList, Resource<OAuthClient>> oAuthClients() {
        return delegate.oAuthClients();
    }

    @Override
    public NonNamespaceOperation<OAuthClientAuthorization, OAuthClientAuthorizationList, Resource<OAuthClientAuthorization>> oAuthClientAuthorizations() {
        return delegate.oAuthClientAuthorizations();
    }

    @Override
    public MixedOperation<OperatorPKI, OperatorPKIList, Resource<OperatorPKI>> operatorPKIs() {
        return delegate.operatorPKIs();
    }

    @Override
    public MixedOperation<EgressRouter, EgressRouterList, Resource<EgressRouter>> egressRouters() {
        return delegate.egressRouters();
    }

    @Override
    public NamespacedInOutCreateable<PodSecurityPolicyReview, PodSecurityPolicyReview> podSecurityPolicyReviews() {
        return delegate.podSecurityPolicyReviews();
    }

    @Override
    public NamespacedInOutCreateable<PodSecurityPolicySelfSubjectReview, PodSecurityPolicySelfSubjectReview> podSecurityPolicySelfSubjectReviews() {
        return delegate.podSecurityPolicySelfSubjectReviews();
    }

    @Override
    public NamespacedInOutCreateable<PodSecurityPolicySubjectReview, PodSecurityPolicySubjectReview> podSecurityPolicySubjectReviews() {
        return delegate.podSecurityPolicySubjectReviews();
    }

    @Override
    public ProjectOperation projects() {
        return delegate.projects();
    }

    @Override
    public ProjectRequestOperation projectrequests() {
        return delegate.projectrequests();
    }

    @Override
    public OpenShiftQuotaAPIGroupDSL quotas() {
        return delegate.quotas();
    }

    @Override
    public MixedOperation<io.fabric8.openshift.api.model.Role, RoleList, Resource<io.fabric8.openshift.api.model.Role>> roles() {
        return delegate.roles();
    }

    @Override
    public MixedOperation<io.fabric8.openshift.api.model.RoleBinding, RoleBindingList, Resource<io.fabric8.openshift.api.model.RoleBinding>> roleBindings() {
        return delegate.roleBindings();
    }

    @Override
    public MixedOperation<Route, RouteList, Resource<Route>> routes() {
        return delegate.routes();
    }

    @Override
    public ParameterMixedOperation<Template, TemplateList, TemplateResource> templates() {
        return delegate.templates();
    }

    @Override
    public MixedOperation<TemplateInstance, TemplateInstanceList, Resource<TemplateInstance>> templateInstances() {
        return delegate.templateInstances();
    }

    @Override
    public OpenShiftTunedAPIGroupDSL tuned() {
        return delegate.tuned();
    }

    @Override
    public NonNamespaceOperation<BrokerTemplateInstance, BrokerTemplateInstanceList, Resource<BrokerTemplateInstance>> brokerTemplateInstances() {
        return delegate.brokerTemplateInstances();
    }

    @Override
    public NonNamespaceOperation<User, UserList, Resource<User>> users() {
        return delegate.users();
    }

    @Override
    public NonNamespaceOperation<RangeAllocation, RangeAllocationList, Resource<RangeAllocation>> rangeAllocations() {
        return delegate.rangeAllocations();
    }

    @Override
    public NonNamespaceOperation<SecurityContextConstraints, SecurityContextConstraintsList, Resource<SecurityContextConstraints>> securityContextConstraints() {
        return delegate.securityContextConstraints();
    }

    @Override
    public InOutCreateable<SubjectAccessReview, SubjectAccessReviewResponse> subjectAccessReviews() {
        return delegate.subjectAccessReviews();
    }

    @Override
    public InOutCreateable<ResourceAccessReview, ResourceAccessReviewResponse> resourceAccessReviews() {
        return delegate.resourceAccessReviews();
    }

    @Override
    public NamespacedInOutCreateable<LocalSubjectAccessReview, SubjectAccessReviewResponse> localSubjectAccessReviews() {
        return delegate.localSubjectAccessReviews();
    }

    @Override
    public NamespacedInOutCreateable<LocalResourceAccessReview, ResourceAccessReviewResponse> localResourceAccessReviews() {
        return delegate.localResourceAccessReviews();
    }

    @Override
    public NamespacedInOutCreateable<SelfSubjectRulesReview, SelfSubjectRulesReview> selfSubjectRulesReviews() {
        return delegate.selfSubjectRulesReviews();
    }

    @Override
    public NamespacedInOutCreateable<SubjectRulesReview, SubjectRulesReview> subjectRulesReviews() {
        return delegate.subjectRulesReviews();
    }

    @Override
    public OpenShiftStorageVersionMigratorApiGroupDSL kubeStorageVersionMigrator() {
        return delegate.kubeStorageVersionMigrator();
    }

    @Override
    public NonNamespaceOperation<ClusterRole, ClusterRoleList, Resource<ClusterRole>> clusterRoles() {
        return delegate.clusterRoles();
    }

    @Override
    public MixedOperation<ClusterRoleBinding, ClusterRoleBindingList, Resource<ClusterRoleBinding>> clusterRoleBindings() {
        return delegate.clusterRoleBindings();
    }

    @Override
    public MixedOperation<RoleBindingRestriction, RoleBindingRestrictionList, Resource<RoleBindingRestriction>> roleBindingRestrictions() {
        return delegate.roleBindingRestrictions();
    }

    @Override
    public NamespacedOpenShiftClient inAnyNamespace() {
        return delegate.inAnyNamespace();
    }

    @Override
    public NamespacedOpenShiftClient inNamespace(String namespace) {
        return delegate.inNamespace(namespace);
    }

    @Override
    public FunctionCallable<NamespacedOpenShiftClient> withRequestConfig(RequestConfig requestConfig) {
        return delegate.withRequestConfig(requestConfig);
    }

    @Override
    public User currentUser() {
        return delegate.currentUser();
    }

    @Override
    public NonNamespaceOperation<Identity, IdentityList, Resource<Identity>> identities() {
        return delegate.identities();
    }

    @Override
    public InOutCreateable<UserIdentityMapping, UserIdentityMapping> userIdentityMappings() {
        return delegate.userIdentityMappings();
    }

    @Override
    public NonNamespaceOperation<UserOAuthAccessToken, UserOAuthAccessTokenList, Resource<UserOAuthAccessToken>> userOAuthAccessTokens() {
        return delegate.userOAuthAccessTokens();
    }

    @Override
    public OpenShiftWhereaboutsAPIGroupDSL whereabouts() {
        return delegate.whereabouts();
    }

    @Override
    public boolean supportsOpenShiftAPIGroup(String s) {
        return delegate.supportsOpenShiftAPIGroup(s);
    }

    @Override
    public boolean isSupported() {
        return delegate.isSupported();
    }

    @Override
    public <C extends Client> Boolean isAdaptable(Class<C> type) {
        return delegate.isAdaptable(type);
    }

    @Override
    public <R extends KubernetesResource> boolean supports(Class<R> type) {
        return delegate.supports(type);
    }

    @Override
    public boolean supports(String apiVersion, String kind) {
        return delegate.supports(apiVersion, kind);
    }

    @Override
    public boolean hasApiGroup(String apiGroup, boolean exact) {
        return delegate.hasApiGroup(apiGroup, exact);
    }

    @Override
    public <C extends Client> C adapt(Class<C> type) {
        return delegate.adapt(type);
    }

    @Override
    public URL getMasterUrl() {
        return delegate.getMasterUrl();
    }

    @Override
    public String getApiVersion() {
        return delegate.getApiVersion();
    }

    @Override
    public String getNamespace() {
        return delegate.getNamespace();
    }

    @Override
    public RootPaths rootPaths() {
        return delegate.rootPaths();
    }

    @Override
    public boolean supportsApiPath(String path) {
        return delegate.supportsApiPath(path);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public APIGroupList getApiGroups() {
        return delegate.getApiGroups();
    }

    @Override
    public APIGroup getApiGroup(String name) {
        return delegate.getApiGroup(name);
    }

    @Override
    public APIResourceList getApiResources(String groupVersion) {
        return delegate.getApiResources(groupVersion);
    }

    @Override
    public <T extends HasMetadata, L extends KubernetesResourceList<T>, R extends Resource<T>> MixedOperation<T, L, R> resources(
            Class<T> resourceType, Class<L> listClass, Class<R> resourceClass) {
        return delegate.resources(resourceType, listClass, resourceClass);
    }

    @Override
    public Client newClient(RequestConfig requestConfig) {
        return delegate.newClient(requestConfig);
    }

    @Override
    public HttpClient getHttpClient() {
        return delegate.getHttpClient();
    }

    @Override
    public Config getConfiguration() {
        return delegate.getConfiguration();
    }

    @Override
    public String raw(String uri, String method, Object payload) {
        return delegate.raw(uri, method, payload);
    }

    public <T extends HasMetadata, L extends KubernetesResourceList<T>> HasMetadataOperationsImpl<T, L> newHasMetadataOperation(
            ResourceDefinitionContext rdContext, Class<T> resourceType, Class<L> listClass) {
        return new HasMetadataOperationsImpl(this, rdContext, resourceType, listClass);
    }
}
