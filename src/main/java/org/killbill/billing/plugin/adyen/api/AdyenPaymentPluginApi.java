/*
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.adyen.api;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.xml.bind.JAXBException;

import org.joda.time.DateTime;
import org.killbill.adyen.recurring.RecurringDetail;
import org.killbill.adyen.recurring.ServiceException;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountData;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.Payment;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentMethod;
import org.killbill.billing.payment.api.PaymentMethodPlugin;
import org.killbill.billing.payment.api.PaymentTransaction;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.GatewayNotification;
import org.killbill.billing.payment.plugin.api.HostedPaymentPageFormDescriptor;
import org.killbill.billing.payment.plugin.api.PaymentMethodInfoPlugin;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.plugin.adyen.api.mapping.PaymentInfoMappingService;
import org.killbill.billing.plugin.adyen.client.AdyenConfigProperties;
import org.killbill.billing.plugin.adyen.client.model.PaymentData;
import org.killbill.billing.plugin.adyen.client.model.PaymentInfo;
import org.killbill.billing.plugin.adyen.client.model.PaymentModificationResponse;
import org.killbill.billing.plugin.adyen.client.model.PaymentServiceProviderResult;
import org.killbill.billing.plugin.adyen.client.model.PurchaseResult;
import org.killbill.billing.plugin.adyen.client.model.SplitSettlementData;
import org.killbill.billing.plugin.adyen.client.model.UserData;
import org.killbill.billing.plugin.adyen.client.notification.AdyenNotificationHandler;
import org.killbill.billing.plugin.adyen.client.notification.AdyenNotificationService;
import org.killbill.billing.plugin.adyen.client.payment.exception.SignatureGenerationException;
import org.killbill.billing.plugin.adyen.client.payment.service.AdyenPaymentServiceProviderHostedPaymentPagePort;
import org.killbill.billing.plugin.adyen.client.payment.service.AdyenPaymentServiceProviderPort;
import org.killbill.billing.plugin.adyen.client.recurring.AdyenRecurringClient;
import org.killbill.billing.plugin.adyen.core.AdyenActivator;
import org.killbill.billing.plugin.adyen.core.AdyenConfigurationHandler;
import org.killbill.billing.plugin.adyen.core.AdyenHostedPaymentPageConfigurationHandler;
import org.killbill.billing.plugin.adyen.core.AdyenRecurringConfigurationHandler;
import org.killbill.billing.plugin.adyen.core.KillbillAdyenNotificationHandler;
import org.killbill.billing.plugin.adyen.dao.AdyenDao;
import org.killbill.billing.plugin.adyen.dao.gen.tables.AdyenPaymentMethods;
import org.killbill.billing.plugin.adyen.dao.gen.tables.AdyenResponses;
import org.killbill.billing.plugin.adyen.dao.gen.tables.records.AdyenPaymentMethodsRecord;
import org.killbill.billing.plugin.adyen.dao.gen.tables.records.AdyenResponsesRecord;
import org.killbill.billing.plugin.api.PluginProperties;
import org.killbill.billing.plugin.api.payment.PluginPaymentPluginApi;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.clock.Clock;
import org.killbill.killbill.osgi.libs.killbill.OSGIConfigPropertiesService;
import org.killbill.killbill.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.killbill.osgi.libs.killbill.OSGIKillbillLogService;
import org.osgi.service.log.LogService;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import static org.killbill.billing.plugin.adyen.api.mapping.UserDataMappingService.toUserData;

public class AdyenPaymentPluginApi extends PluginPaymentPluginApi<AdyenResponsesRecord, AdyenResponses, AdyenPaymentMethodsRecord, AdyenPaymentMethods> {

    // Shared properties
    public static final String PROPERTY_PAYMENT_PROCESSOR_ACCOUNT_ID = "paymentProcessorAccountId";
    public static final String PROPERTY_ACQUIRER = "acquirer";
    public static final String PROPERTY_ACQUIRER_MID = "acquirerMID";
    public static final String PROPERTY_INSTALLMENTS = "installments";
    public static final String PROPERTY_RECURRING_TYPE = "recurringType";
    public static final String PROPERTY_CAPTURE_DELAY_HOURS = "captureDelayHours";
    /**
     * Cont auth disabled validation on adyens side (no cvc required). We practically tell them that the payment data is valid.
     * Should be given as "true" or "false".
     */
    public static final String PROPERTY_CONTINUOUS_AUTHENTICATION = "contAuth";

    // API
    public static final String PROPERTY_RECURRING_DETAIL_ID = "recurringDetailId";

    // 3-D Secure
    public static final String PROPERTY_PA_RES = "PaRes";
    public static final String PROPERTY_MD = "MD";
    public static final String PROPERTY_TERM_URL = "TermUrl";
    public static final String PROPERTY_USER_AGENT = "userAgent";
    public static final String PROPERTY_ACCEPT_HEADER = "acceptHeader";
    public static final String PROPERTY_THREE_D_THRESHOLD = "threeDThreshold";
    public static final String PROPERTY_MPI_DATA_DIRECTORY_RESPONSE = "mpiDataDirectoryResponse";
    public static final String PROPERTY_MPI_DATA_AUTHENTICATION_RESPONSE = "mpiDataAuthenticationResponse";
    public static final String PROPERTY_MPI_DATA_CAVV = "mpiDataCavv";
    public static final String PROPERTY_MPI_DATA_CAVV_ALGORITHM = "mpiDataCavvAlgorithm";
    public static final String PROPERTY_MPI_DATA_XID = "mpiDataXid";
    public static final String PROPERTY_MPI_DATA_ECI = "mpiDataEci";
    public static final String PROPERTY_MPI_IMPLEMENTATION_TYPE = "mpiImplementationType";

    // Credit cards
    public static final String PROPERTY_CC_ISSUER_COUNTRY = "issuerCountry";

    // SEPA
    public static final String PROPERTY_DD_HOLDER_NAME = "ddHolderName";
    public static final String PROPERTY_DD_ACCOUNT_NUMBER = "ddNumber";
    public static final String PROPERTY_DD_BANK_IDENTIFIER_CODE = "ddBic";

    // User data
    public static final String PROPERTY_FIRST_NAME = "firstName";
    public static final String PROPERTY_LAST_NAME = "lastName";
    public static final String PROPERTY_IP = "ip";
    public static final String PROPERTY_CUSTOMER_LOCALE = "customerLocale";
    public static final String PROPERTY_CUSTOMER_ID = "customerId";
    public static final String PROPERTY_EMAIL = "email";

    // HPP
    public static final String PROPERTY_CREATE_PENDING_PAYMENT = "createPendingPayment";
    public static final String PROPERTY_AUTH_MODE = "authMode";
    public static final String PROPERTY_PAYMENT_EXTERNAL_KEY = "paymentExternalKey";
    public static final String PROPERTY_RESULT_URL = "resultUrl";
    public static final String PROPERTY_SERVER_URL = "serverUrl";
    public static final String PROPERTY_SHIP_BEFORE_DATE = "shipBeforeDate";
    public static final String PROPERTY_SKIN_CODE = "skin";
    public static final String PROPERTY_ORDER_DATA = "orderData";
    public static final String PROPERTY_SESSION_VALIDITY = "sessionValidity";
    public static final String PROPERTY_MERCHANT_RETURN_DATA = "merchantReturnData";
    public static final String PROPERTY_ALLOWED_METHODS = "allowedMethods";
    public static final String PROPERTY_BLOCKED_METHODS = "blockedMethods";
    public static final String PROPERTY_BRAND_CODE = "brandCode";
    public static final String PROPERTY_ISSUER_ID = "issuerId";
    public static final String PROPERTY_OFFER_EMAIL = "offerEmail";
    public static final String PROPERTY_HPP_TARGET = "hppTarget";

    // Internals
    public static final String PROPERTY_ADDITIONAL_DATA = "additionalData";
    public static final String PROPERTY_EVENT_CODE = "eventCode";
    public static final String PROPERTY_EVENT_DATE = "eventDate";
    public static final String PROPERTY_MERCHANT_ACCOUNT_CODE = "merchantAccountCode";
    public static final String PROPERTY_MERCHANT_REFERENCE = "merchantReference";
    public static final String PROPERTY_OPERATIONS = "operations";
    public static final String PROPERTY_ORIGINAL_REFERENCE = "originalReference";
    public static final String PROPERTY_PAYMENT_METHOD = "paymentMethod";
    public static final String PROPERTY_PSP_REFERENCE = "pspReference";
    public static final String PROPERTY_REASON = "reason";
    public static final String PROPERTY_SUCCESS = "success";
    public static final String PROPERTY_FROM_HPP = "fromHPP";
    public static final String PROPERTY_FROM_HPP_TRANSACTION_STATUS = "fromHPPTransactionStatus";
    public static final String PROPERTY_PA_REQ = "PaReq";
    public static final String PROPERTY_DCC_AMOUNT_VALUE = "dccAmount";
    public static final String PROPERTY_DCC_AMOUNT_CURRENCY = "dccCurrency";
    public static final String PROPERTY_DCC_SIGNATURE = "dccSignature";
    public static final String PROPERTY_ISSUER_URL = "issuerUrl";

    private final AdyenConfigurationHandler adyenConfigurationHandler;
    private final AdyenHostedPaymentPageConfigurationHandler adyenHppConfigurationHandler;
    private final AdyenRecurringConfigurationHandler adyenRecurringConfigurationHandler;
    private final AdyenDao dao;
    private final AdyenNotificationService adyenNotificationService;

    public AdyenPaymentPluginApi(final AdyenConfigurationHandler adyenConfigurationHandler,
                                 final AdyenHostedPaymentPageConfigurationHandler adyenHppConfigurationHandler,
                                 final AdyenRecurringConfigurationHandler adyenRecurringConfigurationHandler,
                                 final OSGIKillbillAPI killbillApi,
                                 final OSGIConfigPropertiesService osgiConfigPropertiesService,
                                 final OSGIKillbillLogService logService,
                                 final Clock clock,
                                 final AdyenDao dao) throws JAXBException {
        super(killbillApi, osgiConfigPropertiesService, logService, clock, dao);
        this.adyenConfigurationHandler = adyenConfigurationHandler;
        this.adyenHppConfigurationHandler = adyenHppConfigurationHandler;
        this.adyenRecurringConfigurationHandler = adyenRecurringConfigurationHandler;
        this.dao = dao;

        final AdyenNotificationHandler adyenNotificationHandler = new KillbillAdyenNotificationHandler(killbillApi, dao, clock);
        //noinspection RedundantTypeArguments
        this.adyenNotificationService = new AdyenNotificationService(ImmutableList.<AdyenNotificationHandler>of(adyenNotificationHandler));
    }

    @Override
    protected PaymentTransactionInfoPlugin buildPaymentTransactionInfoPlugin(final AdyenResponsesRecord adyenResponsesRecord) {
        return new AdyenPaymentTransactionInfoPlugin(adyenResponsesRecord);
    }

    @Override
    protected PaymentMethodPlugin buildPaymentMethodPlugin(final AdyenPaymentMethodsRecord paymentMethodsRecord) {
        return new AdyenPaymentMethodPlugin(paymentMethodsRecord);
    }

    @Override
    protected PaymentMethodInfoPlugin buildPaymentMethodInfoPlugin(final AdyenPaymentMethodsRecord paymentMethodsRecord) {
        return new AdyenPaymentMethodInfoPlugin(paymentMethodsRecord);
    }

    @Override
    protected String getPaymentMethodId(final AdyenPaymentMethodsRecord paymentMethodsRecord) {
        return paymentMethodsRecord.getKbPaymentMethodId();
    }

    @Override
    public void deletePaymentMethod(final UUID kbAccountId, final UUID kbPaymentMethodId, final Iterable<PluginProperty> properties, final CallContext context) throws PaymentPluginApiException {
        // Retrieve our currently known payment method
        final AdyenPaymentMethodsRecord adyenPaymentMethodsRecord;
        try {
             adyenPaymentMethodsRecord = dao.getPaymentMethod(kbPaymentMethodId, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve payment method", e);
        }

        if (adyenPaymentMethodsRecord.getToken() != null) {
            // Retrieve the associated country for that shopper (and the corresponding merchant account)
            final Account account = getAccount(kbAccountId, context);
            final String pluginPropertyCountry = PluginProperties.findPluginPropertyValue(PROPERTY_COUNTRY, properties);
            final String countryCode = pluginPropertyCountry == null ? account.getCountry() : pluginPropertyCountry;
            final String merchantAccount = getMerchantAccount(countryCode, properties, context);

            final Map additionalData = AdyenDao.fromAdditionalData(adyenPaymentMethodsRecord.getAdditionalData());
            Object customerId = additionalData.get(PROPERTY_CUSTOMER_ID);
            if (customerId == null) {
                customerId = MoreObjects.firstNonNull(account.getExternalKey(), account.getId());
            }

            final AdyenRecurringClient adyenRecurringClient = adyenRecurringConfigurationHandler.getConfigurable(context.getTenantId());
            try {
                adyenRecurringClient.revokeRecurringDetails(countryCode, customerId.toString(), merchantAccount);
            } catch (final ServiceException e) {
                throw new PaymentPluginApiException("Unable to revoke recurring details in Adyen", e);
            }
        }

        super.deletePaymentMethod(kbAccountId, kbPaymentMethodId, properties, context);
    }

    @Override
    public List<PaymentMethodInfoPlugin> getPaymentMethods(final UUID kbAccountId, final boolean refreshFromGateway, final Iterable<PluginProperty> properties, final CallContext context) throws PaymentPluginApiException {
        // If refreshFromGateway isn't set, simply read our tables
        if (!refreshFromGateway) {
            return super.getPaymentMethods(kbAccountId, refreshFromGateway, properties, context);
        }

        // Retrieve our currently known payment methods
        final List<AdyenPaymentMethodsRecord> existingPaymentMethods;
        try {
            existingPaymentMethods = dao.getPaymentMethods(kbAccountId, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve existing payment methods", e);
        }

        // We cannot retrieve recurring details from Adyen without a shopper reference
        if (existingPaymentMethods.isEmpty()) {
            return super.getPaymentMethods(kbAccountId, refreshFromGateway, properties, context);
        }

        // Retrieve the associated country for that shopper (and the corresponding merchant account)
        final Account account = getAccount(kbAccountId, context);
        final String pluginPropertyCountry = PluginProperties.findPluginPropertyValue(PROPERTY_COUNTRY, properties);
        final String countryCode = pluginPropertyCountry == null ? account.getCountry() : pluginPropertyCountry;
        final String merchantAccount = getMerchantAccount(countryCode, properties, context);

        for (final AdyenPaymentMethodsRecord record : Lists.<AdyenPaymentMethodsRecord>reverse(existingPaymentMethods)) {
            if (record.getToken() != null) {
                // Immutable in Adyen -- nothing to do
                continue;
            }

            final Map additionalData = AdyenDao.fromAdditionalData(record.getAdditionalData());

            Object customerId = additionalData.get(PROPERTY_CUSTOMER_ID);
            if (customerId == null) {
                customerId = MoreObjects.firstNonNull(account.getExternalKey(), account.getId());
            }

            Object recurringType = additionalData.get(PROPERTY_RECURRING_TYPE);
            if (recurringType == null) {
                recurringType = MoreObjects.firstNonNull(PluginProperties.findPluginPropertyValue(PROPERTY_RECURRING_TYPE, properties), "RECURRING");
            }

            final AdyenRecurringClient adyenRecurringClient = adyenRecurringConfigurationHandler.getConfigurable(context.getTenantId());

            final List<RecurringDetail> recurringDetailList;
            try {
                recurringDetailList = adyenRecurringClient.getRecurringDetailList(countryCode, customerId.toString(), merchantAccount, recurringType.toString());
            } catch (final ServiceException e) {
                logService.log(LogService.LOG_ERROR, "Unable to retrieve recurring details in Adyen", e);
                continue;
            }
            for (final RecurringDetail recurringDetail : recurringDetailList) {
                final AdyenResponsesRecord formerResponse;
                try {
                    formerResponse = dao.getResponse(recurringDetail.getFirstPspReference());
                } catch (final SQLException e) {
                    logService.log(LogService.LOG_ERROR, "Unable to retrieve adyen response", e);
                    continue;
                }
                if (formerResponse == null) {
                    continue;
                }

                final Payment payment;
                try {
                    payment = killbillAPI.getPaymentApi().getPayment(UUID.fromString(formerResponse.getKbPaymentId()), false, properties, context);
                } catch (final PaymentApiException e) {
                    logService.log(LogService.LOG_ERROR, "Unable to retrieve Payment for externalKey " + recurringDetail.getFirstPspReference(), e);
                    continue;
                }
                if (payment.getPaymentMethodId().toString().equals(record.getKbPaymentMethodId())) {
                    try {
                        dao.setPaymentMethodToken(record.getKbPaymentMethodId(), recurringDetail.getRecurringDetailReference(), context.getTenantId().toString());
                    } catch (final SQLException e) {
                        logService.log(LogService.LOG_ERROR, "Unable to update token", e);
                        continue;
                    }
                }
            }
        }

        return super.getPaymentMethods(kbAccountId, false, properties, context);
    }

    @Override
    public PaymentTransactionInfoPlugin authorizePayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId, final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency, final Iterable<PluginProperty> properties, final CallContext context) throws PaymentPluginApiException {
        return executeInitialTransaction(TransactionType.AUTHORIZE, kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, amount, currency, properties, context);
    }

    @Override
    public PaymentTransactionInfoPlugin capturePayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId, final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency, final Iterable<PluginProperty> properties, final CallContext context) throws PaymentPluginApiException {
        return executeFollowUpTransaction(TransactionType.CAPTURE,
                                          new TransactionExecutor<PaymentModificationResponse>() {
                                              @Override
                                              public PaymentModificationResponse execute(final String merchantAccount, final PaymentData paymentData, final String pspReference, final SplitSettlementData splitSettlementData) {
                                                  final AdyenPaymentServiceProviderPort port = adyenConfigurationHandler.getConfigurable(context.getTenantId());
                                                  return port.capture(merchantAccount, paymentData, pspReference, splitSettlementData);
                                              }
                                          },
                                          kbAccountId,
                                          kbPaymentId,
                                          kbTransactionId,
                                          kbPaymentMethodId,
                                          amount,
                                          currency,
                                          properties,
                                          context);
    }

    @Override
    public PaymentTransactionInfoPlugin purchasePayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId, final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency, final Iterable<PluginProperty> properties, final CallContext context) throws PaymentPluginApiException {
        final AdyenResponsesRecord adyenResponsesRecord;
        try {
            adyenResponsesRecord = dao.updateResponse(kbTransactionId, properties, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("HPP notification came through, but we encountered a database error", e);
        }

        if (adyenResponsesRecord == null) {
            // We don't have any record for that payment: we want to trigger an actual purchase (auto-capture) call
            final String captureDelayHours = PluginProperties.getValue(PROPERTY_CAPTURE_DELAY_HOURS, "0", properties);
            final Iterable<PluginProperty> overriddenProperties = PluginProperties.merge(properties, ImmutableList.<PluginProperty>of(new PluginProperty(PROPERTY_CAPTURE_DELAY_HOURS, captureDelayHours, false)));
            return executeInitialTransaction(TransactionType.PURCHASE, kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, amount, currency, overriddenProperties, context);
        } else {
            // We already have a record for that payment transaction and we just updated the response row with additional properties
            // (the API can be called for instance after the user is redirected back from the HPP to store the PSP reference)
        }

        return buildPaymentTransactionInfoPlugin(adyenResponsesRecord);
    }

    @Override
    public PaymentTransactionInfoPlugin voidPayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId, final UUID kbPaymentMethodId, final Iterable<PluginProperty> properties, final CallContext context) throws PaymentPluginApiException {
        return executeFollowUpTransaction(TransactionType.VOID,
                                          new TransactionExecutor<PaymentModificationResponse>() {
                                              @Override
                                              public PaymentModificationResponse execute(final String merchantAccount, final PaymentData paymentData, final String pspReference, final SplitSettlementData splitSettlementData) {
                                                  final AdyenPaymentServiceProviderPort port = adyenConfigurationHandler.getConfigurable(context.getTenantId());
                                                  return port.cancel(merchantAccount, paymentData, pspReference, splitSettlementData);
                                              }
                                          },
                                          kbAccountId,
                                          kbPaymentId,
                                          kbTransactionId,
                                          kbPaymentMethodId,
                                          null,
                                          null,
                                          properties,
                                          context);
    }

    @Override
    public PaymentTransactionInfoPlugin creditPayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId, final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency, final Iterable<PluginProperty> properties, final CallContext context) throws PaymentPluginApiException {
        // See https://docs.adyen.com/developers/api-manual#carddepositcardfundtransfercft
        return executeInitialTransaction(TransactionType.CREDIT, kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, amount, currency, properties, context);
    }

    @Override
    public PaymentTransactionInfoPlugin refundPayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId, final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency, final Iterable<PluginProperty> properties, final CallContext context) throws PaymentPluginApiException {
        return executeFollowUpTransaction(TransactionType.REFUND,
                                          new TransactionExecutor<PaymentModificationResponse>() {
                                              @Override
                                              public PaymentModificationResponse execute(final String merchantAccount, final PaymentData paymentData, final String pspReference, final SplitSettlementData splitSettlementData) {
                                                  final AdyenPaymentServiceProviderPort providerPort = adyenConfigurationHandler.getConfigurable(context.getTenantId());
                                                  return providerPort.refund(merchantAccount, paymentData, pspReference, splitSettlementData);
                                              }
                                          },
                                          kbAccountId,
                                          kbPaymentId,
                                          kbTransactionId,
                                          kbPaymentMethodId,
                                          amount,
                                          currency,
                                          properties,
                                          context);
    }

    // HPP

    @Override
    public HostedPaymentPageFormDescriptor buildFormDescriptor(final UUID kbAccountId, final Iterable<PluginProperty> customFields, final Iterable<PluginProperty> properties, final CallContext context) throws PaymentPluginApiException {
        //noinspection unchecked
        final Iterable<PluginProperty> mergedProperties = PluginProperties.merge(customFields, properties);

        final Account account = getAccount(kbAccountId, context);

        final String amountString = PluginProperties.findPluginPropertyValue(PROPERTY_AMOUNT, mergedProperties);
        Preconditions.checkState(!Strings.isNullOrEmpty(amountString), "amount not specified");
        final BigDecimal amount = new BigDecimal(amountString);
        final String currencyString = PluginProperties.findPluginPropertyValue(PROPERTY_CURRENCY, properties);
        final Currency currency = currencyString == null ? account.getCurrency() : Currency.valueOf(currencyString);

        final PaymentData paymentData = buildPaymentData(account, amount, currency, mergedProperties, context);
        final UserData userData = toUserData(account, mergedProperties);

        final boolean shouldCreatePendingPayment = Boolean.valueOf(PluginProperties.findPluginPropertyValue(PROPERTY_CREATE_PENDING_PAYMENT, mergedProperties));
        Payment pendingPayment = null;
        if (shouldCreatePendingPayment) {
            final boolean authMode = Boolean.valueOf(PluginProperties.findPluginPropertyValue(PROPERTY_AUTH_MODE, mergedProperties));
            pendingPayment = createPendingPayment(authMode, account, paymentData, context);
        }

        try {
            // Need to store on disk the mapping payment <-> user because Adyen's notification won't provide the latter
            //noinspection unchecked
            dao.addHppRequest(kbAccountId,
                              pendingPayment == null ? null : pendingPayment.getId(),
                              pendingPayment == null ? null : pendingPayment.getTransactions().get(0).getId(),
                              pendingPayment == null ? paymentData.getPaymentTransactionExternalKey() : pendingPayment.getTransactions().get(0).getExternalKey(),
                              PluginProperties.toMap(mergedProperties),
                              clock.getUTCNow(),
                              context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to store HPP request", e);
        }

        final AdyenPaymentServiceProviderHostedPaymentPagePort hostedPaymentPagePort = adyenHppConfigurationHandler.getConfigurable(context.getTenantId());

        final String merchantAccount = getMerchantAccount(paymentData, properties, context);
        final SplitSettlementData splitSettlementData = null;

        final Map<String, String> formParameter;
        try {
            formParameter = hostedPaymentPagePort.getFormParameter(merchantAccount, paymentData, userData, splitSettlementData);
        } catch (final SignatureGenerationException e) {
            throw new PaymentPluginApiException("Unable to generate signature", e);
        }

        final String hppTarget = PluginProperties.getValue(PROPERTY_HPP_TARGET, hostedPaymentPagePort.getAdyenConfigProperties().getHppTarget(), properties);
        return new AdyenHostedPaymentPageFormDescriptor(kbAccountId, hppTarget, PluginProperties.buildPluginProperties(formParameter));
    }

    @Override
    public GatewayNotification processNotification(final String notification, final Iterable<PluginProperty> properties, final CallContext context) throws PaymentPluginApiException {
        final String notificationResponse = adyenNotificationService.handleNotifications(notification);
        return new AdyenGatewayNotification(notificationResponse);
    }

    private abstract static class TransactionExecutor<T> {

        public T execute(final String merchantAccount, final PaymentData paymentData, final UserData userData, final SplitSettlementData splitSettlementData) {
            throw new UnsupportedOperationException();
        }

        public T execute(final String merchantAccount, final PaymentData paymentData, final String pspReference, final SplitSettlementData splitSettlementData) {
            throw new UnsupportedOperationException();
        }
    }

    private PaymentTransactionInfoPlugin executeInitialTransaction(final TransactionType transactionType,
                                                                   final UUID kbAccountId,
                                                                   final UUID kbPaymentId,
                                                                   final UUID kbTransactionId,
                                                                   final UUID kbPaymentMethodId,
                                                                   final BigDecimal amount,
                                                                   final Currency currency,
                                                                   final Iterable<PluginProperty> properties,
                                                                   final CallContext context) throws PaymentPluginApiException {
        return executeInitialTransaction(transactionType,
                                         new TransactionExecutor<PurchaseResult>() {
                                             @Override
                                             public PurchaseResult execute(final String merchantAccount, final PaymentData paymentData, final UserData userData, final SplitSettlementData splitSettlementData) {
                                                 final AdyenPaymentServiceProviderPort adyenPort = adyenConfigurationHandler.getConfigurable(context.getTenantId());
                                                 if (hasPreviousAdyenRespondseRecord(kbPaymentId, kbTransactionId.toString(), context)) {
                                                     // We are completing a 3D-S payment
                                                     return adyenPort.authorize3DSecure(merchantAccount, paymentData, userData, splitSettlementData);
                                                 } else {
                                                     // We are creating a new transaction (AUTHORIZE, PURCHASE or CREDIT)
                                                     if (transactionType == TransactionType.CREDIT) {
                                                         return adyenPort.credit(merchantAccount, paymentData, userData, splitSettlementData);
                                                     } else {
                                                         return adyenPort.authorise(merchantAccount, paymentData, userData, splitSettlementData);
                                                     }
                                                 }
                                             }
                                         },
                                         kbAccountId,
                                         kbPaymentId,
                                         kbTransactionId,
                                         kbPaymentMethodId,
                                         amount,
                                         currency,
                                         properties,
                                         context);
    }

    private PaymentTransactionInfoPlugin executeInitialTransaction(final TransactionType transactionType,
                                                                   final TransactionExecutor<PurchaseResult> transactionExecutor,
                                                                   final UUID kbAccountId,
                                                                   final UUID kbPaymentId,
                                                                   final UUID kbTransactionId,
                                                                   final UUID kbPaymentMethodId,
                                                                   final BigDecimal amount,
                                                                   final Currency currency,
                                                                   final Iterable<PluginProperty> properties,
                                                                   final TenantContext context) throws PaymentPluginApiException {
        final boolean fromHPP = Boolean.valueOf(PluginProperties.findPluginPropertyValue(PROPERTY_FROM_HPP, properties));
        if (fromHPP) {
            // We are either processing a notification (see KillbillAdyenNotificationHandler) or creating a PENDING payment for HPP (see buildFormDescriptor)
            final DateTime utcNow = clock.getUTCNow();
            try {
                final AdyenResponsesRecord adyenResponsesRecord = dao.addAdyenResponse(kbAccountId, kbPaymentId, kbTransactionId, transactionType, amount, currency, PluginProperties.toMap(properties), utcNow, context.getTenantId());
                return buildPaymentTransactionInfoPlugin(adyenResponsesRecord);
            } catch (final SQLException e) {
                throw new PaymentPluginApiException("HPP payment came through, but we encountered a database error", e);
            }
        }

        final Account account = getAccount(kbAccountId, context);

        final AdyenPaymentMethodsRecord nonNullPaymentMethodsRecord = getAdyenPaymentMethodsRecord(kbPaymentMethodId, context);
        // Pull extra properties from the payment method (such as the customerId)
        final Iterable<PluginProperty> additionalPropertiesFromRecord = buildPaymentMethodPlugin(nonNullPaymentMethodsRecord).getProperties();
        //noinspection unchecked
        final Iterable<PluginProperty> mergedProperties = PluginProperties.merge(additionalPropertiesFromRecord, properties);
        final PaymentData paymentData = buildPaymentData(account, kbPaymentId, kbTransactionId, nonNullPaymentMethodsRecord, amount, currency, mergedProperties, context);
        final UserData userData = toUserData(account, mergedProperties);
        final SplitSettlementData splitSettlementData = null;
        final DateTime utcNow = clock.getUTCNow();

        final String merchantAccount = getMerchantAccount(paymentData, properties, context);

        final PurchaseResult response = transactionExecutor.execute(merchantAccount, paymentData, userData, splitSettlementData);
        try {
            dao.addResponse(kbAccountId, kbPaymentId, kbTransactionId, transactionType, amount, currency, response, utcNow, context.getTenantId());
            return new AdyenPaymentTransactionInfoPlugin(kbPaymentId, kbTransactionId, transactionType, amount, currency, utcNow, response);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Payment went through, but we encountered a database error. Payment details: " + response.toString(), e);
        }
    }

    private PaymentTransactionInfoPlugin executeFollowUpTransaction(final TransactionType transactionType,
                                                                    final TransactionExecutor<PaymentModificationResponse> transactionExecutor,
                                                                    final UUID kbAccountId,
                                                                    final UUID kbPaymentId,
                                                                    final UUID kbTransactionId,
                                                                    final UUID kbPaymentMethodId,
                                                                    @Nullable final BigDecimal amount,
                                                                    @Nullable final Currency currency,
                                                                    final Iterable<PluginProperty> properties,
                                                                    final TenantContext context) throws PaymentPluginApiException {
        final boolean fromHPP = Boolean.valueOf(PluginProperties.findPluginPropertyValue(PROPERTY_FROM_HPP, properties));
        if (fromHPP) {
            // We are processing a notification (see KillbillAdyenNotificationHandler)
            final DateTime utcNow = clock.getUTCNow();
            try {
                final AdyenResponsesRecord adyenResponsesRecord = dao.addAdyenResponse(kbAccountId, kbPaymentId, kbTransactionId, transactionType, amount, currency, PluginProperties.toMap(properties), utcNow, context.getTenantId());
                return buildPaymentTransactionInfoPlugin(adyenResponsesRecord);
            } catch (final SQLException e) {
                throw new PaymentPluginApiException("HPP payment came through, but we encountered a database error", e);
            }
        }

        final Account account = getAccount(kbAccountId, context);

        final String pspReference;
        try {
            final AdyenResponsesRecord previousResponse = dao.getSuccessfulAuthorizationResponse(kbPaymentId, context.getTenantId());
            if (previousResponse == null) {
                throw new PaymentPluginApiException(null, "Unable to retrieve previous payment response for kbTransactionId " + kbTransactionId);
            }
            pspReference = previousResponse.getPspReference();
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve previous payment response for kbTransactionId " + kbTransactionId, e);
        }

        final AdyenPaymentMethodsRecord nonNullPaymentMethodsRecord = getAdyenPaymentMethodsRecord(kbPaymentMethodId, context);
        final PaymentData paymentData = buildPaymentData(account, kbPaymentId, kbTransactionId, nonNullPaymentMethodsRecord, amount, currency, properties, context);
        final SplitSettlementData splitSettlementData = null;
        final DateTime utcNow = clock.getUTCNow();

        final String merchantAccount = getMerchantAccount(paymentData, properties, context);

        final PaymentModificationResponse response = transactionExecutor.execute(merchantAccount, paymentData, pspReference, splitSettlementData);
        final Optional<PaymentServiceProviderResult> paymentServiceProviderResult;
        if (response.isTechnicallySuccessful()) {
            paymentServiceProviderResult = Optional.of(PaymentServiceProviderResult.RECEIVED);
        } else {
            paymentServiceProviderResult = Optional.<PaymentServiceProviderResult>absent();
        }

        try {
            dao.addResponse(kbAccountId, kbPaymentId, kbTransactionId, transactionType, amount, currency, response, utcNow, context.getTenantId());
            return new AdyenPaymentTransactionInfoPlugin(kbPaymentId, kbTransactionId, transactionType, amount, currency, paymentServiceProviderResult, utcNow, response);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Payment went through, but we encountered a database error. Payment details: " + (response.toString()), e);
        }
    }

    // For API
    private PaymentData buildPaymentData(final AccountData account, final UUID kbPaymentId, final UUID kbTransactionId, final AdyenPaymentMethodsRecord paymentMethodsRecord, final BigDecimal amount, final Currency currency, final Iterable<PluginProperty> properties, final TenantContext context) throws PaymentPluginApiException {
        final Payment payment;
        try {
            payment = killbillAPI.getPaymentApi().getPayment(kbPaymentId, false, properties, context);
        } catch (final PaymentApiException e) {
            throw new PaymentPluginApiException(String.format("Unable to retrieve kbPaymentId='%s'", kbPaymentId), e);
        }

        final PaymentTransaction paymentTransaction = Iterables.<PaymentTransaction>find(payment.getTransactions(),
                                                                                         new Predicate<PaymentTransaction>() {
                                                                                             @Override
                                                                                             public boolean apply(final PaymentTransaction input) {
                                                                                                 return kbTransactionId.equals(input.getId());
                                                                                             }
                                                                                         });

        final PaymentInfo paymentInfo = buildPaymentInfo(account, paymentMethodsRecord, properties, context);

        return new PaymentData<PaymentInfo>(amount, currency, paymentTransaction.getExternalKey(), paymentInfo);
    }

    // For HPP
    private PaymentData buildPaymentData(final AccountData account, final BigDecimal amount, final Currency currency, final Iterable<PluginProperty> properties, final TenantContext context) {
        final PaymentInfo paymentInfo = buildPaymentInfo(account, null, properties, context);
        final String paymentTransactionExternalKey = PluginProperties.getValue(PROPERTY_PAYMENT_EXTERNAL_KEY, UUID.randomUUID().toString(), properties);
        return new PaymentData<PaymentInfo>(amount, currency, paymentTransactionExternalKey, paymentInfo);
    }

    private PaymentInfo buildPaymentInfo(final AccountData account, @Nullable final AdyenPaymentMethodsRecord paymentMethodsRecord, final Iterable<PluginProperty> properties, final TenantContext context) {
        // A bit of a hack - it would be nice to be able to isolate AdyenConfigProperties
        final AdyenConfigProperties adyenConfigProperties = adyenHppConfigurationHandler.getConfigurable(context.getTenantId()).getAdyenConfigProperties();
        return PaymentInfoMappingService.toPaymentInfo(adyenConfigProperties, clock, account, paymentMethodsRecord, properties);
    }

    /**
     * There is the option not to use the adyen payment method table to retrieve payment data but to always provide
     * it as plugin properties. In this case an empty record (null object) could help.
     */
    private AdyenPaymentMethodsRecord emptyRecord(@Nullable final UUID kbPaymentMethodId) {
        final AdyenPaymentMethodsRecord record = new AdyenPaymentMethodsRecord();
        if (kbPaymentMethodId != null) {
            record.setKbPaymentMethodId(kbPaymentMethodId.toString());
        }
        return record;
    }

    private AdyenPaymentMethodsRecord getAdyenPaymentMethodsRecord(@Nullable final UUID kbPaymentMethodId, final TenantContext context) {
        AdyenPaymentMethodsRecord paymentMethodsRecord = null;

        if (kbPaymentMethodId != null) {
            try {
                paymentMethodsRecord = dao.getPaymentMethod(kbPaymentMethodId, context.getTenantId());
            } catch (final SQLException e) {
                logService.log(LogService.LOG_WARNING, "Failed to retrieve payment method " + kbPaymentMethodId, e);
            }
        }

        return MoreObjects.firstNonNull(paymentMethodsRecord, emptyRecord(kbPaymentMethodId));
    }

    private Payment createPendingPayment(final boolean authMode, final Account account, final PaymentData paymentData, final CallContext context) throws PaymentPluginApiException {
        final UUID kbPaymentId = null;
        final String paymentTransactionExternalKey = paymentData.getPaymentTransactionExternalKey();
        //noinspection UnnecessaryLocalVariable
        final String paymentExternalKey = paymentTransactionExternalKey;
        final ImmutableMap<String, Object> purchasePropertiesMap = ImmutableMap.<String, Object>of(AdyenPaymentPluginApi.PROPERTY_FROM_HPP, true,
                                                                                                   AdyenPaymentPluginApi.PROPERTY_FROM_HPP_TRANSACTION_STATUS, PaymentPluginStatus.PENDING.toString());
        final Iterable<PluginProperty> purchaseProperties = PluginProperties.buildPluginProperties(purchasePropertiesMap);

        try {
            final UUID kbPaymentMethodId = getAdyenKbPaymentMethodId(account.getId(), context);
            if (authMode) {
                return killbillAPI.getPaymentApi().createAuthorization(account,
                                                                       kbPaymentMethodId,
                                                                       kbPaymentId,
                                                                       paymentData.getAmount(),
                                                                       paymentData.getCurrency(),
                                                                       paymentExternalKey,
                                                                       paymentTransactionExternalKey,
                                                                       purchaseProperties,
                                                                       context);
            } else {
                return killbillAPI.getPaymentApi().createPurchase(account,
                                                                  kbPaymentMethodId,
                                                                  kbPaymentId,
                                                                  paymentData.getAmount(),
                                                                  paymentData.getCurrency(),
                                                                  paymentExternalKey,
                                                                  paymentTransactionExternalKey,
                                                                  purchaseProperties,
                                                                  context);
            }
        } catch (final PaymentApiException e) {
            throw new PaymentPluginApiException("Failed to record purchase", e);
        }
    }

    // Could be shared (see KillbillAdyenNotificationHandler)
    private UUID getAdyenKbPaymentMethodId(final UUID kbAccountId, final TenantContext context) throws PaymentApiException {
        //noinspection RedundantTypeArguments
        return Iterables.<PaymentMethod>find(killbillAPI.getPaymentApi().getAccountPaymentMethods(kbAccountId, false, ImmutableList.<PluginProperty>of(), context),
                              new Predicate<PaymentMethod>() {
                                  @Override
                                  public boolean apply(final PaymentMethod paymentMethod) {
                                      return AdyenActivator.PLUGIN_NAME.equals(paymentMethod.getPluginName());
                                  }
                              }).getId();
    }

    private boolean hasPreviousAdyenRespondseRecord(final UUID kbPaymentId, final String kbPaymentTransactionId, final CallContext context) {
        try {
            final AdyenResponsesRecord previousAuthorizationResponse = dao.getSuccessfulAuthorizationResponse(kbPaymentId, context.getTenantId());
            return previousAuthorizationResponse != null && previousAuthorizationResponse.getKbPaymentTransactionId().equals(kbPaymentTransactionId);
        } catch (final SQLException e) {
            logService.log(LogService.LOG_ERROR, "Failed to get previous AdyenResponsesRecord", e);
            return false;
        }
    }

    private String getMerchantAccount(final PaymentData paymentData, final Iterable<PluginProperty> properties, final TenantContext context) {
        final String countryIsoCode = paymentData.getPaymentInfo().getCountry();
        return getMerchantAccount(countryIsoCode, properties, context);
    }

    private String getMerchantAccount(final String countryCode, final Iterable<PluginProperty> properties, final TenantContext context) {
        final String pluginPropertyMerchantAccount = PluginProperties.findPluginPropertyValue(PROPERTY_PAYMENT_PROCESSOR_ACCOUNT_ID, properties);
        if (pluginPropertyMerchantAccount != null) {
            return pluginPropertyMerchantAccount;
        }

        // A bit of a hack - it would be nice to be able to isolate AdyenConfigProperties
        final AdyenConfigProperties adyenConfigProperties = adyenHppConfigurationHandler.getConfigurable(context.getTenantId()).getAdyenConfigProperties();
        return adyenConfigProperties.getMerchantAccount(countryCode);
    }
}
