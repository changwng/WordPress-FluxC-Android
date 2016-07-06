package org.wordpress.android.stores.network.rest.wpcom.site;

import android.support.annotation.NonNull;

import com.android.volley.Request.Method;
import com.android.volley.RequestQueue;
import com.android.volley.Response.ErrorListener;
import com.android.volley.Response.Listener;
import com.android.volley.VolleyError;

import org.json.JSONException;
import org.json.JSONObject;
import org.wordpress.android.stores.Dispatcher;
import org.wordpress.android.stores.Payload;
import org.wordpress.android.stores.generated.SiteActionBuilder;
import org.wordpress.android.stores.model.SiteModel;
import org.wordpress.android.stores.model.SitesModel;
import org.wordpress.android.stores.network.UserAgent;
import org.wordpress.android.stores.network.rest.wpcom.BaseWPComRestClient;
import org.wordpress.android.stores.network.rest.wpcom.WPCOMREST;
import org.wordpress.android.stores.network.rest.wpcom.WPComGsonRequest;
import org.wordpress.android.stores.network.rest.wpcom.account.NewAccountResponse;
import org.wordpress.android.stores.network.rest.wpcom.auth.AccessToken;
import org.wordpress.android.stores.network.rest.wpcom.auth.AppSecrets;
import org.wordpress.android.stores.network.rest.wpcom.site.SiteWPComRestResponse.SitesResponse;
import org.wordpress.android.stores.store.SiteStore.NewSiteError;
import org.wordpress.android.stores.store.SiteStore.SiteVisibility;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class SiteRestClient extends BaseWPComRestClient {
    private final AppSecrets mAppSecrets;

    public static class NewSiteResponsePayload implements Payload {
        public NewSiteResponsePayload() {
        }
        public NewSiteError errorType;
        public String errorMessage;
        public boolean isError;
        public boolean dryRun;
    }

    @Inject
    public SiteRestClient(Dispatcher dispatcher, RequestQueue requestQueue, AppSecrets appSecrets,
                          AccessToken accessToken, UserAgent userAgent) {
        super(dispatcher, requestQueue, accessToken, userAgent);
        mAppSecrets = appSecrets;
    }

    public void pullSites() {
        String url = WPCOMREST.ME_SITES.getUrlV1_1();
        final WPComGsonRequest<SitesResponse> request = new WPComGsonRequest<>(Method.GET,
                url, null, SitesResponse.class,
                new Listener<SitesResponse>() {
                    @Override
                    public void onResponse(SitesResponse response) {
                        SitesModel sites = new SitesModel();
                        for (SiteWPComRestResponse siteResponse : response.sites) {
                            sites.add(siteResponseToSiteModel(siteResponse));
                        }
                        mDispatcher.dispatch(SiteActionBuilder.newUpdateSitesAction(sites));
                    }
                },
                new ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        AppLog.e(T.API, "Volley error", error);
                        // TODO: Error, dispatch network error
                    }
                }
        );
        add(request);
    }

    public void pullSite(final SiteModel site) {
        String url = WPCOMREST.SITES.getUrlV1_1() + site.getSiteId();
        final WPComGsonRequest<SiteWPComRestResponse> request = new WPComGsonRequest<>(Method.GET,
                url, null, SiteWPComRestResponse.class,
                new Listener<SiteWPComRestResponse>() {
                    @Override
                    public void onResponse(SiteWPComRestResponse response) {
                        SiteModel site = siteResponseToSiteModel(response);
                        mDispatcher.dispatch(SiteActionBuilder.newUpdateSiteAction(site));
                    }
                },
                new ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        AppLog.e(T.API, "Volley error", error);
                        // TODO: Error, dispatch network error
                    }
                }
        );
        add(request);
    }

    public void newSite(@NonNull String siteName, @NonNull String siteTitle, @NonNull String language,
                        @NonNull SiteVisibility visibility, final boolean dryRun) {
        String url = WPCOMREST.SITES_NEW.getUrlV1();
        Map<String, String> params = new HashMap<>();
        params.put("blog_name", siteName);
        params.put("blog_title", siteTitle);
        params.put("lang_id", language);
        params.put("public", visibility.toString());
        params.put("validate", dryRun ? "1" : "0");
        params.put("client_id", mAppSecrets.getAppId());
        params.put("client_secret", mAppSecrets.getAppSecret());
        add(new WPComGsonRequest<>(Method.POST, url, params, NewAccountResponse.class,
                new Listener<NewAccountResponse>() {
                    @Override
                    public void onResponse(NewAccountResponse response) {
                        NewSiteResponsePayload payload = new NewSiteResponsePayload();
                        payload.isError = false;
                        payload.dryRun = dryRun;
                        mDispatcher.dispatch(SiteActionBuilder.newCreatedNewSiteAction(payload));
                    }
                },
                new ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        AppLog.e(T.API, new String(error.networkResponse.data));
                        NewSiteResponsePayload payload = volleyErrorToAccountResponsePayload(error);
                        payload.dryRun = dryRun;
                        mDispatcher.dispatch(SiteActionBuilder.newCreatedNewSiteAction(payload));
                    }
                }
        ));
    }

    private SiteModel siteResponseToSiteModel(SiteWPComRestResponse from) {
        SiteModel site = new SiteModel();
        site.setSiteId(from.ID);
        site.setUrl(from.URL);
        site.setName(from.name);
        site.setDescription(from.description);
        site.setIsJetpack(from.jetpack);
        site.setIsVisible(from.visible);
        // Depending of user's role, options could be "hidden", for instance an "Author" can't read blog options.
        if (from.options != null) {
            site.setIsFeaturedImageSupported(from.options.featured_images_enabled);
            site.setIsVideoPressSupported(from.options.videopress_enabled);
            site.setAdminUrl(from.options.admin_url);
        }
        site.setIsWPCom(true);
        return site;
    }

    private NewSiteResponsePayload volleyErrorToAccountResponsePayload(VolleyError error) {
        NewSiteResponsePayload payload = new NewSiteResponsePayload();
        payload.isError = true;
        payload.errorType = NewSiteError.GENERIC_ERROR;
        if (error.networkResponse != null && error.networkResponse.data != null) {
            String jsonString = new String(error.networkResponse.data);
            try {
                JSONObject errorObj = new JSONObject(jsonString);
                payload.errorType = errorStringToErrorType((String) errorObj.get("error"));
                payload.errorMessage = (String) errorObj.get("message");
            } catch (JSONException e) {
                // Do nothing (keep default error)
            }
        }
        return payload;
    }

    private NewSiteError errorStringToErrorType(String error) {
        if (error.equals("blog_name_required")) {
            return NewSiteError.SITE_NAME_REQUIRED;
        }
        if (error.equals("blog_name_not_allowed")) {
            return NewSiteError.SITE_NAME_NOT_ALLOWED;
        }
        if (error.equals("blog_name_must_be_at_least_four_characters")) {
            return NewSiteError.SITE_NAME_MUST_BE_AT_LEAST_FOUR_CHARACTERS;
        }
        if (error.equals("blog_name_must_be_less_than_sixty_four_characters")) {
            return NewSiteError.SITE_NAME_MUST_BE_LESS_THAN_SIXTY_FOUR_CHARACTERS;
        }
        if (error.equals("blog_name_contains_invalid_characters")) {
            return NewSiteError.SITE_NAME_CONTAINS_INVALID_CHARACTERS;
        }
        if (error.equals("blog_name_cant_be_used")) {
            return NewSiteError.SITE_NAME_CANT_BE_USED;
        }
        if (error.equals("blog_name_only_lowercase_letters_and_numbers")) {
            return NewSiteError.SITE_NAME_ONLY_LOWERCASE_LETTERS_AND_NUMBERS;
        }
        if (error.equals("blog_name_must_include_letters")) {
            return NewSiteError.SITE_NAME_MUST_INCLUDE_LETTERS;
        }
        if (error.equals("blog_name_exists")) {
            return NewSiteError.SITE_NAME_EXISTS;
        }
        if (error.equals("blog_name_reserved")) {
            return NewSiteError.SITE_NAME_RESERVED;
        }
        if (error.equals("blog_name_reserved_but_may_be_available")) {
            return NewSiteError.SITE_NAME_RESERVED_BUT_MAY_BE_AVAILABLE;
        }
        if (error.equals("blog_name_invalid")) {
            return NewSiteError.SITE_NAME_INVALID;
        }
        if (error.equals("blog_title_invalid")) {
            return NewSiteError.SITE_TITLE_INVALID;
        }
        return NewSiteError.GENERIC_ERROR;
    }
}
