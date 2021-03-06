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

package org.killbill.billing.plugin.adyen.client.payment.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.plugin.adyen.TestRemoteBase;
import org.killbill.billing.plugin.adyen.client.model.PaymentData;
import org.killbill.billing.plugin.adyen.client.model.UserData;
import org.killbill.billing.plugin.adyen.client.model.paymentinfo.WebPaymentFrontend;
import org.killbill.billing.plugin.util.http.QueryComputer;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TestRemoteAdyenPaymentServiceProviderHostedPaymentPagePort extends TestRemoteBase {

    @Test(groups = "slow")
    public void testRedirectHPP() throws Exception {
        final WebPaymentFrontend paymentInfo = new WebPaymentFrontend();
        paymentInfo.setCountry(DEFAULT_COUNTRY);
        paymentInfo.setSkinCode(adyenConfigProperties.getSkin(paymentInfo.getCountry()));
        paymentInfo.setShipBeforeDate("2019-01-01");
        paymentInfo.setSessionValidity("2019-01-01T12:12:12Z");
        paymentInfo.setTermUrl("http://killbill.io?q=test+adyen+redirect+success");

        final PaymentData<WebPaymentFrontend> paymentData = new PaymentData<WebPaymentFrontend>(BigDecimal.TEN, Currency.USD, UUID.randomUUID().toString(), paymentInfo);

        final UserData userData = new UserData();
        userData.setShopperLocale(new Locale("en", DEFAULT_COUNTRY));

        final Map<String, String> formParameter = adyenPaymentServiceProviderHostedPaymentPagePort.getFormParameter(merchantAccount, paymentData, userData, null);
        final String formUrl = adyenConfigProperties.getHppTarget();

        Assert.assertNotNull(formParameter.get("merchantReference"));
        Assert.assertNotNull(formParameter.get("paymentAmount"));
        Assert.assertNotNull(formParameter.get("currencyCode"));
        Assert.assertNotNull(formParameter.get("shipBeforeDate"));
        Assert.assertNotNull(formParameter.get("skinCode"));
        Assert.assertNotNull(formParameter.get("merchantAccount"));
        Assert.assertNotNull(formParameter.get("sessionValidity"));
        Assert.assertNotNull(formUrl);

        // For manual testing
        final String fullQueryString = QueryComputer.URL_ENCODING_ENABLED_QUERY_COMPUTER.computeFullQueryString(null, formParameter);
        System.out.println("Redirect to " + formUrl + "?" + fullQueryString);
        System.out.flush();
    }
}
