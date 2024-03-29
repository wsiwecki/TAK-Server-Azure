/*
    The ClassificationService is based on the Information Security Marking Metadata specification defined at
    https://www.dni.gov/index.php/who-we-are/organizations/ic-cio/ic-cio-related-menus/ic-cio-related-links/ic-technical-specifications/information-security-marking-metadata
 */

package com.bbn.marti.classification.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bbn.marti.remote.config.CoreConfigFacade;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import org.apache.ignite.IgniteCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.beans.factory.annotation.Autowired;

import org.ism.invoker.ApiClient;
import org.ism.api.DefaultApi;
import org.ism.model.User;
import org.ism.model.CanAccessDto;
import org.ism.model.CanAccessResponse;

import com.bbn.marti.remote.CoreConfig;
import com.bbn.marti.remote.groups.UserClassification;
import tak.server.cache.classification.ClassificationCacheHelper;
import tak.server.cot.CotEventContainer;


public class ClassificationServiceImpl implements ClassificationService {

    private static final Logger logger = LoggerFactory.getLogger(ClassificationServiceImpl.class);

    @Autowired
    private ClassificationCacheHelper classificationCacheHelper;

    private DefaultApi ismApi;


    public boolean canAccess(UserClassification userClassification, String itemClassification) {

        // if we don't have the item's classification, fallback to network classification
        if (Strings.isNullOrEmpty(itemClassification)) {
            if (logger.isDebugEnabled()) {
                logger.debug("itemClassification not found, using network classification");
            }

            CoreConfig config = CoreConfigFacade.getInstance();
            if (config.getRemoteConfiguration().getVbm() != null
                    && config.getRemoteConfiguration().getVbm().isEnabled()) {
                itemClassification = config.getRemoteConfiguration().getVbm().getNetworkClassification();
            }
       }

        // if we don't have enough input, deny access if strict mode enabled
        if (userClassification == null || Strings.isNullOrEmpty(itemClassification)) {
            if (logger.isDebugEnabled()) {
                logger.debug(("userClassification and/or itemClassification not found"));
            }
            return !CoreConfigFacade.getInstance().getRemoteConfiguration().getVbm().isIsmStrictEnforcing();
        }

        // see if we have an item itemClassification result for this userClassification
        Boolean result = null;
        IgniteCache<Object, Object> classificationCache = classificationCacheHelper.getClassificationCache();
        Map<String, Boolean> itemClassificationCache = (Map<String, Boolean>)classificationCache.get(userClassification);
        if (itemClassificationCache == null) {
            itemClassificationCache = new ConcurrentHashMap<>();
            classificationCache.put(userClassification, itemClassificationCache);
            if (logger.isDebugEnabled()) {
                try {
                    logger.debug("creating itemClassificationCache for {}",
                            new ObjectMapper().writeValueAsString(userClassification));
                } catch (JsonProcessingException e) {}
            }
        } else {
            result = itemClassificationCache.get(itemClassification);
        }

        // nothing in the cache
        if (result == null) {
            // call the ism service for this user, item pair
            result = ismCanAccess(userClassification, itemClassification);

            if (logger.isDebugEnabled()) {
                try {
                    logger.debug("ismCanAccess returned {} for {} - {}", result,
                            new ObjectMapper().writeValueAsString(userClassification), itemClassification);
                } catch (JsonProcessingException e) {}
            }

            // update the cache
            itemClassificationCache.put(itemClassification, result);
            classificationCache.put(userClassification, itemClassificationCache);

        } else {
            if (logger.isDebugEnabled()) {
                try {
                    logger.debug("returning cached result {} for {} - {}", result,
                            new ObjectMapper().writeValueAsString(userClassification), itemClassification);
                } catch (JsonProcessingException e) {}
            }
        }

        return result;
    }

    public boolean canAccess(UserClassification userClassification, CotEventContainer cotEventContainer) {

        String itemClassification = null;

        if (!Strings.isNullOrEmpty(cotEventContainer.getAccess())) {
            itemClassification = cotEventContainer.getAccess();

            if (!Strings.isNullOrEmpty(cotEventContainer.getCaveat())) {
                itemClassification += "//" + cotEventContainer.getCaveat();
            }

            if (!Strings.isNullOrEmpty(cotEventContainer.getReleaseableTo())) {
                itemClassification += "//" + cotEventContainer.getReleaseableTo();
            }
        }

        return canAccess(userClassification, itemClassification);
    }

    private Boolean ismCanAccess(UserClassification userClassification, String itemClassification) {
        if (Strings.isNullOrEmpty(CoreConfigFacade.getInstance().getRemoteConfiguration().getVbm().getIsmUrl())) {
            if (logger.isDebugEnabled()) {
                logger.debug("ismUrl is not set");
            }
            return !CoreConfigFacade.getInstance().getRemoteConfiguration().getVbm().isIsmStrictEnforcing();
        }

        User ismUser = new User();
        if (userClassification.getAccms() != null) {
            ismUser.getAccms().addAll(userClassification.getAccms());
        }
        if (!Strings.isNullOrEmpty(userClassification.getCountry())) {
            ismUser.setCountry(User.CountryEnum.valueOf(userClassification.getCountry()));
        }
        if (userClassification.getClassifications() != null) {
            userClassification.getClassifications().stream()
                    .forEach(c -> ismUser.getClassifications().add(User.ClassificationsEnum.fromValue(c)));
        }
        if (userClassification.getSciControls() != null) {
            userClassification.getSciControls().stream()
                    .forEach(sci -> ismUser.getSciControls().add(User.SciControlsEnum.fromValue(sci)));
        }

        CanAccessDto canAccessDto = new CanAccessDto();
        canAccessDto.user(ismUser);
        canAccessDto.addMarkingsItem(itemClassification);

        CanAccessResponse response = getIsmApi().ismControllerCanAccess(canAccessDto);
        return Boolean.valueOf(response.getResult());
    }

    private DefaultApi getIsmApi() {
        if (ismApi == null) {
            RestTemplateBuilder restTemplateBuilder = new RestTemplateBuilder();

            if (CoreConfigFacade.getInstance().getRemoteConfiguration().getVbm().getIsmConnectTimeoutSeconds() != -1) {
                restTemplateBuilder.setConnectTimeout(Duration.ofSeconds(
                        CoreConfigFacade.getInstance().getRemoteConfiguration().getVbm().getIsmConnectTimeoutSeconds()));
            }

            if (CoreConfigFacade.getInstance().getRemoteConfiguration().getVbm().getIsmReadTimeoutSeconds() != -1) {
                restTemplateBuilder.setReadTimeout(Duration.ofSeconds(
                        CoreConfigFacade.getInstance().getRemoteConfiguration().getVbm().getIsmReadTimeoutSeconds()));
            }

            ApiClient apiClient = new ApiClient(restTemplateBuilder.build());
            apiClient.setBasePath(CoreConfigFacade.getInstance().getRemoteConfiguration().getVbm().getIsmUrl());

            ismApi = new DefaultApi(apiClient);
        }

        return ismApi;
    }
}
