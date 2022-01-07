/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.event.handler.notification.util;

import org.apache.axiom.om.OMElement;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.databridge.commons.StreamDefinition;
import org.wso2.carbon.databridge.commons.exception.MalformedStreamDefinitionException;
import org.wso2.carbon.email.mgt.exceptions.I18nEmailMgtException;
import org.wso2.carbon.email.mgt.model.EmailTemplate;
import org.wso2.carbon.event.publisher.core.EventPublisherService;
import org.wso2.carbon.event.publisher.core.config.EventPublisherConfiguration;
import org.wso2.carbon.event.publisher.core.exception.EventPublisherConfigurationException;
import org.wso2.carbon.event.stream.core.EventStreamService;
import org.wso2.carbon.event.stream.core.exception.EventStreamConfigurationException;
import org.wso2.carbon.identity.core.ServiceURLBuilder;
import org.wso2.carbon.identity.core.URLBuilderException;
import org.wso2.carbon.identity.core.util.IdentityConfigParser;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.notification.NotificationConstants;
import org.wso2.carbon.identity.event.handler.notification.email.bean.Notification;
import org.wso2.carbon.identity.event.handler.notification.exception.NotificationRuntimeException;
import org.wso2.carbon.identity.event.handler.notification.internal.NotificationHandlerDataHolder;
import org.wso2.carbon.identity.governance.model.UserIdentityClaim;
import org.wso2.carbon.user.api.Claim;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.api.UserStoreManager;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.service.RealmService;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.wso2.carbon.identity.event.handler.notification.NotificationConstants.EmailNotification.CARBON_PRODUCT_URL_TEMPLATE_PLACEHOLDER;
import static org.wso2.carbon.identity.event.handler.notification.NotificationConstants.EmailNotification.CARBON_PRODUCT_URL_WITH_USER_TENANT_TEMPLATE_PLACEHOLDER;

public class NotificationUtil {

    private static final Log log = LogFactory.getLog(NotificationUtil.class);

    private static final String USER_IDENTITY_CLAIMS = "UserIdentityClaims";

    public static Map<String, String> getUserClaimValues(String userName, UserStoreManager userStoreManager) {

        Claim[] userClaims;
        Map<String, String> claimsMap = new HashMap<String, String>();
        try {
            userClaims = userStoreManager.getUserClaimValues(userName, UserCoreConstants.DEFAULT_PROFILE);
            if (userClaims != null) {
                for (Claim userClaim : userClaims) {
                    claimsMap.put(userClaim.getClaimUri(), userClaim.getValue());
                }
            }
            UserIdentityClaim userIdentityClaims =
                    (UserIdentityClaim) IdentityUtil.threadLocalProperties.get().get(USER_IDENTITY_CLAIMS);
            Map<String, String> userIdentityDataMap;
            if (userIdentityClaims == null) {
                userIdentityDataMap = new HashMap<>();
            } else {
                userIdentityDataMap = userIdentityClaims.getUserIdentityDataMap();
            }
            for (String key : userIdentityDataMap.keySet()) {
                if (!claimsMap.containsKey(key)) {
                    claimsMap.put(key, userIdentityDataMap.get(key));
                }
            }
        } catch (UserStoreException e) {
            String domainNameProperty = getUserStoreDomainName(userStoreManager);
            String message = null;
            if (StringUtils.isNotBlank(domainNameProperty)) {
                message = "Error occurred while retrieving user claim values for user " + userName + " in user store "
                        + domainNameProperty + " in tenant " + getTenantDomain(userStoreManager);
            } else {
                message = "Error occurred while retrieving user claim values for user " + userName + " in tenant "
                        + getTenantDomain(userStoreManager);
            }
            log.error(message, e);
        }

        return claimsMap;
    }

    public static Map<String, String> getUserClaimValues(String userName, String domainName, String tenantDomain)
            throws IdentityEventException {

        RealmService realmService = NotificationHandlerDataHolder.getInstance().getRealmService();
        int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
        UserStoreManager userStoreManager = null;
        try {
            userStoreManager = realmService.getTenantUserRealm(tenantId).getUserStoreManager();
            if (userStoreManager == null) {
                String message = "Error occurred while retrieving userStoreManager for tenant " + tenantDomain;
                throw new IdentityEventException(message);
            } else if (userStoreManager instanceof AbstractUserStoreManager) {
                userStoreManager = ((AbstractUserStoreManager) userStoreManager).getSecondaryUserStoreManager(domainName);
            }
        } catch (UserStoreException e) {
            String message = "Error occurred while retrieving user claim values for user " + userName + " in user " +
                    "store " + domainName + " in tenant " + tenantDomain;
            throw new IdentityEventException(message, e);
        }
        return getUserClaimValues(userName, userStoreManager);
    }

    /**
     * Set place holder values for email templates.
     *
     * @param emailTemplate   {@link org.wso2.carbon.email.mgt.model.EmailTemplate}
     * @param placeHolderData List of place holder data
     * @param userClaims      List of user claims
     * @return Place holder data
     */
    public static Map<String, String> getPlaceholderValues(EmailTemplate emailTemplate,
                                                           Map<String, String> placeHolderData, Map<String, String> userClaims) {

        Map<String, String> configFilePlaceholders = getConfigFilePlaceholders();

        // Having a body is mandatory.
        List<String> placeHolders = new ArrayList<>(extractPlaceHolders(emailTemplate.getBody()));
        if (StringUtils.isNotEmpty(emailTemplate.getSubject())) {
            placeHolders.addAll(extractPlaceHolders(emailTemplate.getSubject()));
        }
        if (StringUtils.isNotEmpty(emailTemplate.getFooter())) {
            placeHolders.addAll(extractPlaceHolders(emailTemplate.getFooter()));
        }

        for (String placeHolder : placeHolders) {
            // Setting config file place holders.
            if (placeHolder.startsWith(NotificationConstants.EmailNotification.IDENTITY_TEMPLATE_VALUE_PREFIX)) {
                String key = placeHolder.substring(placeHolder.lastIndexOf(".") + 1);
                String value = configFilePlaceholders.getOrDefault(key, "");
                placeHolderData.put(placeHolder, value);
            }
            if (userClaims != null && !userClaims.isEmpty()) {
                if (placeHolder.contains(NotificationConstants.EmailNotification.USER_CLAIM_PREFIX + "."
                        + NotificationConstants.EmailNotification.IDENTITY_CLAIM_PREFIX)) {
                    String identityClaim = userClaims.get(NotificationConstants.EmailNotification.WSO2_CLAIM_URI
                            + NotificationConstants.EmailNotification.IDENTITY_CLAIM_PREFIX + "/" + placeHolder
                            .substring(placeHolder.indexOf(".", placeHolder.indexOf("identity")) + 1));
                    if (StringUtils.isNotEmpty(identityClaim)) {
                        placeHolderData.put(placeHolder, identityClaim);
                    }
                } else if (placeHolder.contains(NotificationConstants.EmailNotification.USER_CLAIM_PREFIX)) {
                    String userClaim = userClaims
                            .get(NotificationConstants.EmailNotification.WSO2_CLAIM_URI + placeHolder
                                    .substring(placeHolder.indexOf(".", placeHolder.indexOf("claim")) + 1));
                    if (StringUtils.isNotEmpty(userClaim)) {
                        placeHolderData.put(placeHolder, userClaim);
                    }
                }
            }
        }
        // Building the server url.
        String serverURL;
        String carbonUrlWithUserTenant;
        try {
            serverURL = ServiceURLBuilder.create().build().getAbsolutePublicURL();
            carbonUrlWithUserTenant = ServiceURLBuilder.create().build().getAbsolutePublicUrlWithoutPath();
            if (IdentityTenantUtil.isTenantQualifiedUrlsEnabled()) {
                carbonUrlWithUserTenant = ServiceURLBuilder.create().build().getAbsolutePublicUrlWithoutPath() + "/t" +
                        "/" + placeHolderData.get("tenant-domain");
            }
        } catch (URLBuilderException e) {
            throw NotificationRuntimeException.error("Error while building the server url.", e);
        }

        placeHolderData.put(CARBON_PRODUCT_URL_TEMPLATE_PLACEHOLDER, serverURL);
        placeHolderData.put(CARBON_PRODUCT_URL_WITH_USER_TENANT_TEMPLATE_PLACEHOLDER, carbonUrlWithUserTenant);
        return placeHolderData;
    }

    public static Map<String, String> getConfigFilePlaceholders() {

        IdentityConfigParser configParser = IdentityConfigParser.getInstance();
        OMElement placeHolderElem = configParser.getConfigElement(
                NotificationConstants.EmailNotification.TEMPLATE_PLACEHOLDERS_ELEM);
        if (placeHolderElem == null) {
            return Collections.emptyMap();
        }

        Iterator iterator = placeHolderElem.getChildrenWithLocalName(
                NotificationConstants.EmailNotification.TEMPLATE_PLACEHOLDER_ELEM);
        if (iterator == null) {
            return Collections.emptyMap();
        }

        Map<String, String> placeholderMap = new HashMap<>();
        while (iterator.hasNext()) {
            OMElement omElement = (OMElement) iterator.next();
            if (omElement != null) {
                String key = omElement.getAttributeValue(
                        new QName(NotificationConstants.EmailNotification.TEMPLATE_PLACEHOLDER_KEY_ATTRIB));
                String value = omElement.getText();
                placeholderMap.put(key, value);
            }
        }
        return placeholderMap;
    }

    public static List<String> extractPlaceHolders(String value) {

        String exp = "\\{\\{(.*?)\\}\\}";
        Pattern pattern = Pattern.compile(exp);
        Matcher matcher = pattern.matcher(value);
        List<String> placeHolders = new ArrayList<>();
        while (matcher.find()) {
            String group = matcher.group().replace("{{", "").replace("}}", "");
            placeHolders.add(group);
        }
        return placeHolders;
    }

    public static String getUserStoreDomainName(UserStoreManager userStoreManager) {

        String domainNameProperty = null;
        if (userStoreManager instanceof org.wso2.carbon.user.core.UserStoreManager) {
            domainNameProperty = ((org.wso2.carbon.user.core.UserStoreManager)
                    userStoreManager).getRealmConfiguration()
                    .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);
            if (StringUtils.isBlank(domainNameProperty)) {
                domainNameProperty = IdentityUtil.getPrimaryDomainName();
            }
        }
        return domainNameProperty;
    }

    public static String getTenantDomain(UserStoreManager userStoreManager) {

        try {
            return IdentityTenantUtil.getTenantDomain(userStoreManager.getTenantId());
        } catch (UserStoreException e) {
            throw NotificationRuntimeException.error("Error when getting the tenant domain.", e);
        }
    }

    public static void deployStream(String streamName, String streamVersion, String streamId)
            throws NotificationRuntimeException {

        try {
            EventStreamService service = NotificationHandlerDataHolder.getInstance().getEventStreamService();
            StreamDefinition streamDefinition = new StreamDefinition(streamName, streamVersion, streamId);
            service.addEventStreamDefinition(streamDefinition);
        } catch (MalformedStreamDefinitionException e) {
            throw NotificationRuntimeException.error("Error occurred due to a malformed stream definition.", e);
        } catch (EventStreamConfigurationException e) {
            throw NotificationRuntimeException.error("Error in deploying a stream.", e);
        }
    }

    public static void deployPublisher(EventPublisherConfiguration eventPublisherConfiguration) throws NotificationRuntimeException {

        EventPublisherService eventPublisherService = NotificationHandlerDataHolder.getInstance().getEventPublisherService();
        try {
            eventPublisherService.deployEventPublisherConfiguration(eventPublisherConfiguration);
        } catch (EventPublisherConfigurationException e) {
            throw NotificationRuntimeException.error("Error in deploying a publisher.", e);
        }
    }

    public static Notification buildNotification(Event event, Map<String, String> placeHolderData)
            throws IdentityEventException, NotificationRuntimeException {
        //send-to parameter will be set by the event senders. Here it is first read from the request parameter and
        //if it is not there, then assume this sent-to parameter should read from user's email claim only.
        String sendTo = placeHolderData.get(NotificationConstants.EmailNotification.ARBITRARY_SEND_TO);
        Map<String, String> userClaims = new HashMap<>();
        String notificationEvent = (String) event.getEventProperties().get(NotificationConstants.EmailNotification.EMAIL_TEMPLATE_TYPE);
        String username = (String) event.getEventProperties().get(IdentityEventConstants.EventProperty.USER_NAME);
        org.wso2.carbon.user.core.UserStoreManager userStoreManager = (org.wso2.carbon.user.core.UserStoreManager) event.getEventProperties().get(
                IdentityEventConstants.EventProperty.USER_STORE_MANAGER);
        String userStoreDomainName = (String) event.getEventProperties().get(IdentityEventConstants.EventProperty.USER_STORE_DOMAIN);
        String tenantDomain = (String) event.getEventProperties().get(IdentityEventConstants.EventProperty.TENANT_DOMAIN);
        String sendFrom = (String) event.getEventProperties().get(NotificationConstants.EmailNotification.ARBITRARY_SEND_FROM);

        if (StringUtils.isNotBlank(username) && userStoreManager != null) {
            userClaims = NotificationUtil.getUserClaimValues(username, userStoreManager);
        } else if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(userStoreDomainName) &&
                StringUtils.isNotBlank(tenantDomain)) {
            userClaims = NotificationUtil.getUserClaimValues(username, userStoreDomainName, tenantDomain);
        }

        String locale = NotificationConstants.EmailNotification.LOCALE_DEFAULT;
        if (userClaims.containsKey(NotificationConstants.EmailNotification.CLAIM_URI_LOCALE)) {
            locale = userClaims.get(NotificationConstants.EmailNotification.CLAIM_URI_LOCALE);
        }
        //Only sendTo value read from claims if it is not set the event sender.
        if (StringUtils.isEmpty(sendTo)) {
            if (userClaims.containsKey(NotificationConstants.EmailNotification.CLAIM_URI_EMAIL)) {
                sendTo = userClaims.get(NotificationConstants.EmailNotification.CLAIM_URI_EMAIL);
            }
            if (StringUtils.isEmpty(sendTo)) {
                throw new IdentityEventException("Email notification sending failed. " +
                        "Sending email address is not configured for the user.");
            }
        }

        EmailTemplate emailTemplate;
        try {
            emailTemplate = NotificationHandlerDataHolder.getInstance().getEmailTemplateManager().getEmailTemplate(notificationEvent, locale, tenantDomain);
        } catch (I18nEmailMgtException e) {
            String message = "Error when retrieving template from tenant registry.";
            throw NotificationRuntimeException.error(message, e);
        }

        // this is added to change the copyright year in the email templates dynamically.
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        placeHolderData.put("current-year", String.valueOf(currentYear));

        NotificationUtil.getPlaceholderValues(emailTemplate, placeHolderData, userClaims);

        Notification.EmailNotificationBuilder builder =
                new Notification.EmailNotificationBuilder(sendTo);
        builder.setSendFrom(sendFrom);
        builder.setTemplate(emailTemplate);
        builder.setPlaceHolderData(placeHolderData);
        Notification emailNotification = builder.build();
        return emailNotification;
    }
}

