/*
 * Copyright (C) 2009 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.activities;

import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import org.odk.collect.android.R;
import org.odk.collect.android.activities.viewmodels.FormDownloadListViewModel;
import org.odk.collect.android.adapters.FormDownloadListAdapter;
import org.odk.collect.android.analytics.Analytics;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.dao.FormsDao;
import org.odk.collect.android.formentry.RefreshFormListDialogFragment;
import org.odk.collect.android.formmanagement.FormApiExceptionMapper;
import org.odk.collect.android.formmanagement.ServerFormDetails;
import org.odk.collect.android.formmanagement.ServerFormsDetailsFetcher;
import org.odk.collect.android.injection.DaggerUtils;
import org.odk.collect.android.listeners.DownloadFormsTaskListener;
import org.odk.collect.android.listeners.FormListDownloaderListener;
import org.odk.collect.android.listeners.PermissionListener;
import org.odk.collect.android.network.NetworkStateProvider;
import org.odk.collect.android.openrosa.HttpCredentialsInterface;
import org.odk.collect.android.openrosa.api.FormApiException;
import org.odk.collect.android.storage.StorageInitializer;
import org.odk.collect.android.tasks.DownloadFormListTask;
import org.odk.collect.android.tasks.DownloadFormsTask;
import org.odk.collect.android.utilities.ApplicationConstants;
import org.odk.collect.android.utilities.AuthDialogUtility;
import org.odk.collect.android.utilities.DialogUtils;
import org.odk.collect.android.utilities.MultiFormDownloader;
import org.odk.collect.android.utilities.PermissionUtils;
import org.odk.collect.android.utilities.ToastUtils;
import org.odk.collect.android.utilities.WebCredentialsUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

import javax.inject.Inject;

import timber.log.Timber;

/**
 * Responsible for displaying, adding and deleting all the valid forms in the forms directory. One
 * caveat. If the server requires authentication, a dialog will pop up asking when you request the
 * form list. If somehow you manage to wait long enough and then try to download selected forms and
 * your authorization has timed out, it won't again ask for authentication, it will just throw a
 * 401
 * and you'll have to hit 'refresh' where it will ask for credentials again. Technically a server
 * could point at other servers requiring authentication to download the forms, but the current
 * implementation in Collect doesn't allow for that. Mostly this is just because it's a pain in the
 * butt to keep track of which forms we've downloaded and where we're needing to authenticate. I
 * think we do something similar in the instanceuploader task/activity, so should change the
 * implementation eventually.
 *
 * @author Carl Hartung (carlhartung@gmail.com)
 */
public class FormDownloadListActivity extends FormListActivity implements FormListDownloaderListener,
        DownloadFormsTaskListener, AuthDialogUtility.AuthDialogUtilityResultListener,
        AdapterView.OnItemClickListener, RefreshFormListDialogFragment.RefreshFormListDialogFragmentListener {
    private static final String FORM_DOWNLOAD_LIST_SORTING_ORDER = "formDownloadListSortingOrder";

    public static final String DISPLAY_ONLY_UPDATED_FORMS = "displayOnlyUpdatedForms";
    private static final String BUNDLE_SELECTED_COUNT = "selectedcount";

    public static final String FORMNAME = "formname";
    private static final String FORMDETAIL_KEY = "formdetailkey";
    public static final String FORMID_DISPLAY = "formiddisplay";

    public static final String FORM_ID_KEY = "formid";
    private static final String FORM_VERSION_KEY = "formversion";

    private AlertDialog alertDialog;
    private ProgressDialog cancelDialog;
    private Button downloadButton;

    private DownloadFormListTask downloadFormListTask;
    private DownloadFormsTask downloadFormsTask;
    private Button toggleButton;

    private final ArrayList<HashMap<String, String>> filteredFormList = new ArrayList<>();

    private static final boolean EXIT = true;
    private static final boolean DO_NOT_EXIT = false;

    private boolean displayOnlyUpdatedForms;

    private FormDownloadListViewModel viewModel;

    @Inject
    WebCredentialsUtils webCredentialsUtils;

    @Inject
    ServerFormsDetailsFetcher serverFormsDetailsFetcher;

    @Inject
    NetworkStateProvider connectivityProvider;

    @Inject
    Analytics analytics;

    @Inject
    FormsDao formsDao;

    @Inject
    PermissionUtils permissionUtils;

    @Inject
    StorageInitializer storageInitializer;

    @Inject
    MultiFormDownloader multiFormDownloader;

    @SuppressWarnings("unchecked")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DaggerUtils.getComponent(this).inject(this);

        setContentView(R.layout.form_download_list);
        setTitle(getString(R.string.get_forms));

        viewModel = new ViewModelProvider(this, new FormDownloadListViewModel.Factory(analytics))
                .get(FormDownloadListViewModel.class);

        // This activity is accessed directly externally
        permissionUtils.requestStoragePermissions(this, new PermissionListener() {
            @Override
            public void granted() {
                // must be at the beginning of any activity that can be called from an external intent
                try {
                    storageInitializer.createOdkDirsOnStorage();
                } catch (RuntimeException e) {
                    DialogUtils.showDialog(DialogUtils.createErrorDialog(FormDownloadListActivity.this, e.getMessage(), EXIT), FormDownloadListActivity.this);
                    return;
                }

                init(savedInstanceState);
            }

            @Override
            public void denied() {
                // The activity has to finish because ODK Collect cannot function without these permissions.
                finish();
            }
        });
    }

    private void init(Bundle savedInstanceState) {
        Bundle bundle = getIntent().getExtras();
        if (bundle != null) {
            if (bundle.containsKey(DISPLAY_ONLY_UPDATED_FORMS)) {
                displayOnlyUpdatedForms = (boolean) bundle.get(DISPLAY_ONLY_UPDATED_FORMS);
            }

            if (bundle.containsKey(ApplicationConstants.BundleKeys.FORM_IDS)) {
                viewModel.setDownloadOnlyMode(true);
                viewModel.setFormIdsToDownload(bundle.getStringArray(ApplicationConstants.BundleKeys.FORM_IDS));

                if (viewModel.getFormIdsToDownload() == null) {
                    setReturnResult(false, "Form Ids is null", null);
                    finish();
                }

                if (bundle.containsKey(ApplicationConstants.BundleKeys.URL)) {
                    viewModel.setUrl(bundle.getString(ApplicationConstants.BundleKeys.URL));

                    if (bundle.containsKey(ApplicationConstants.BundleKeys.USERNAME)
                            && bundle.containsKey(ApplicationConstants.BundleKeys.PASSWORD)) {
                        viewModel.setUsername(bundle.getString(ApplicationConstants.BundleKeys.USERNAME));
                        viewModel.setPassword(bundle.getString(ApplicationConstants.BundleKeys.PASSWORD));
                    }
                }
            }
        }

        downloadButton = findViewById(R.id.add_button);
        downloadButton.setEnabled(listView.getCheckedItemCount() > 0);
        downloadButton.setOnClickListener(v -> {
            ArrayList<ServerFormDetails> filesToDownload = getFilesToDownload();
            viewModel.logDownloadAnalyticsEvent(formsDao.getCount(),
                    webCredentialsUtils.getServerUrlFromPreferences());
            startFormsDownload(filesToDownload);
        });

        toggleButton = findViewById(R.id.toggle_button);
        toggleButton.setEnabled(false);
        toggleButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadButton.setEnabled(toggleChecked(listView));
                toggleButtonLabel(toggleButton, listView);
                viewModel.clearSelectedFormIds();
                if (listView.getCheckedItemCount() == listView.getCount()) {
                    for (HashMap<String, String> map : viewModel.getFormList()) {
                        viewModel.addSelectedFormId(map.get(FORMDETAIL_KEY));
                    }
                }
            }
        });

        Button refreshButton = findViewById(R.id.refresh_button);
        refreshButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                viewModel.setLoadingCanceled(false);
                viewModel.clearFormList();
                updateAdapter();
                clearChoices();
                downloadFormList();
            }
        });

        if (savedInstanceState != null) {
            // how many items we've selected
            // Android should keep track of this, but broken on rotate...
            if (savedInstanceState.containsKey(BUNDLE_SELECTED_COUNT)) {
                downloadButton.setEnabled(savedInstanceState.getInt(BUNDLE_SELECTED_COUNT) > 0);
            }
        }

        filteredFormList.addAll(viewModel.getFormList());

        if (getLastCustomNonConfigurationInstance() instanceof DownloadFormListTask) {
            downloadFormListTask = (DownloadFormListTask) getLastCustomNonConfigurationInstance();
            if (downloadFormListTask.getStatus() == AsyncTask.Status.FINISHED) {
                DialogUtils.dismissDialog(RefreshFormListDialogFragment.class, getSupportFragmentManager());
                downloadFormsTask = null;
            }
        } else if (getLastCustomNonConfigurationInstance() instanceof DownloadFormsTask) {
            downloadFormsTask = (DownloadFormsTask) getLastCustomNonConfigurationInstance();
            if (downloadFormsTask.getStatus() == AsyncTask.Status.FINISHED) {
                DialogUtils.dismissDialog(RefreshFormListDialogFragment.class, getSupportFragmentManager());
                downloadFormsTask = null;
            }
        } else if (viewModel.getFormDetailsByFormId().isEmpty()
                && getLastCustomNonConfigurationInstance() == null
                && !viewModel.wasLoadingCanceled()) {
            // first time, so get the formlist
            downloadFormList();
        }

        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setItemsCanFocus(false);

        sortingOptions = new int[]{
                R.string.sort_by_name_asc, R.string.sort_by_name_desc
        };
    }

    private void clearChoices() {
        listView.clearChoices();
        downloadButton.setEnabled(false);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        toggleButtonLabel(toggleButton, listView);
        downloadButton.setEnabled(listView.getCheckedItemCount() > 0);

        if (listView.isItemChecked(position)) {
            viewModel.addSelectedFormId(((HashMap<String, String>) listView.getAdapter().getItem(position)).get(FORMDETAIL_KEY));
        } else {
            viewModel.removeSelectedFormId(((HashMap<String, String>) listView.getAdapter().getItem(position)).get(FORMDETAIL_KEY));
        }
    }

    /**
     * Starts the download task and shows the progress dialog.
     */
    private void downloadFormList() {
        if (!connectivityProvider.isDeviceOnline()) {
            ToastUtils.showShortToast(R.string.no_connection);

            if (viewModel.isDownloadOnlyMode()) {
                setReturnResult(false, getString(R.string.no_connection), viewModel.getFormResults());
                finish();
            }
        } else {
            viewModel.clearFormDetailsByFormId();
            DialogUtils.showIfNotShowing(RefreshFormListDialogFragment.class, getSupportFragmentManager());

            if (downloadFormListTask != null
                    && downloadFormListTask.getStatus() != AsyncTask.Status.FINISHED) {
                return; // we are already doing the download!!!
            } else if (downloadFormListTask != null) {
                downloadFormListTask.setDownloaderListener(null);
                downloadFormListTask.cancel(true);
                downloadFormListTask = null;
            }

            if (viewModel.isDownloadOnlyMode()) {
                // Handle external app download case with different server
                downloadFormListTask = new DownloadFormListTask(serverFormsDetailsFetcher);
                downloadFormListTask.setAlternateCredentials(webCredentialsUtils, viewModel.getUrl(), viewModel.getUsername(), viewModel.getPassword());
                downloadFormListTask.setDownloaderListener(this);
                downloadFormListTask.execute();
            } else {
                downloadFormListTask = new DownloadFormListTask(serverFormsDetailsFetcher);
                downloadFormListTask.setDownloaderListener(this);
                downloadFormListTask.execute();
            }
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        if (PermissionUtils.areStoragePermissionsGranted(this)) {
            updateAdapter();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(BUNDLE_SELECTED_COUNT, listView.getCheckedItemCount());
    }

    @Override
    protected String getSortingOrderKey() {
        return FORM_DOWNLOAD_LIST_SORTING_ORDER;
    }

    @Override
    protected void updateAdapter() {
        CharSequence charSequence = getFilterText();
        filteredFormList.clear();
        if (charSequence.length() > 0) {
            for (HashMap<String, String> form : viewModel.getFormList()) {
                if (form.get(FORMNAME).toLowerCase(Locale.US).contains(charSequence.toString().toLowerCase(Locale.US))) {
                    filteredFormList.add(form);
                }
            }
        } else {
            filteredFormList.addAll(viewModel.getFormList());
        }
        sortList();
        if (listView.getAdapter() == null) {
            listView.setAdapter(new FormDownloadListAdapter(this, filteredFormList, viewModel.getFormDetailsByFormId()));
        } else {
            FormDownloadListAdapter formDownloadListAdapter = (FormDownloadListAdapter) listView.getAdapter();
            formDownloadListAdapter.setFromIdsToDetails(viewModel.getFormDetailsByFormId());
            formDownloadListAdapter.notifyDataSetChanged();
        }
        toggleButton.setEnabled(!filteredFormList.isEmpty());
        checkPreviouslyCheckedItems();
        toggleButtonLabel(toggleButton, listView);
    }

    @Override
    protected void checkPreviouslyCheckedItems() {
        listView.clearChoices();
        for (int i = 0; i < listView.getCount(); i++) {
            HashMap<String, String> item =
                    (HashMap<String, String>) listView.getAdapter().getItem(i);
            if (viewModel.getSelectedFormIds().contains(item.get(FORMDETAIL_KEY))) {
                listView.setItemChecked(i, true);
            }
        }
    }

    private void sortList() {
        Collections.sort(filteredFormList, new Comparator<HashMap<String, String>>() {
            @Override
            public int compare(HashMap<String, String> lhs, HashMap<String, String> rhs) {
                if (getSortingOrder().equals(SORT_BY_NAME_ASC)) {
                    return lhs.get(FORMNAME).compareToIgnoreCase(rhs.get(FORMNAME));
                } else {
                    return rhs.get(FORMNAME).compareToIgnoreCase(lhs.get(FORMNAME));
                }
            }
        });
    }

    private ArrayList<ServerFormDetails> getFilesToDownload() {
        ArrayList<ServerFormDetails> filesToDownload = new ArrayList<>();

        SparseBooleanArray sba = listView.getCheckedItemPositions();
        for (int i = 0; i < listView.getCount(); i++) {
            if (sba.get(i, false)) {
                HashMap<String, String> item =
                        (HashMap<String, String>) listView.getAdapter().getItem(i);
                filesToDownload.add(viewModel.getFormDetailsByFormId().get(item.get(FORMDETAIL_KEY)));
            }
        }
        return filesToDownload;
    }

    /**
     * starts the task to download the selected forms, also shows progress dialog
     */
    @SuppressWarnings("unchecked")
    private void startFormsDownload(@NonNull ArrayList<ServerFormDetails> filesToDownload) {
        int totalCount = filesToDownload.size();
        if (totalCount > 0) {
            // show dialog box
            DialogUtils.showIfNotShowing(RefreshFormListDialogFragment.class, getSupportFragmentManager());

            downloadFormsTask = new DownloadFormsTask(multiFormDownloader);
            downloadFormsTask.setDownloaderListener(this);

            if (viewModel.getUrl() != null) {
                if (viewModel.getUsername() != null && viewModel.getPassword() != null) {
                    webCredentialsUtils.saveCredentials(viewModel.getUrl(), viewModel.getUsername(), viewModel.getPassword());
                } else {
                    webCredentialsUtils.clearCredentials(viewModel.getUrl());
                }
            }

            downloadFormsTask.execute(filesToDownload);
        } else {
            ToastUtils.showShortToast(R.string.noselect_error);
        }
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        if (downloadFormsTask != null) {
            return downloadFormsTask;
        } else {
            return downloadFormListTask;
        }
    }

    @Override
    protected void onDestroy() {
        if (downloadFormListTask != null) {
            downloadFormListTask.setDownloaderListener(null);
        }
        if (downloadFormsTask != null) {
            downloadFormsTask.setDownloaderListener(null);
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        if (downloadFormListTask != null) {
            downloadFormListTask.setDownloaderListener(this);
        }
        if (downloadFormsTask != null) {
            downloadFormsTask.setDownloaderListener(this);
        }
        if (viewModel.isAlertShowing()) {
            createAlertDialog(viewModel.getAlertTitle(), viewModel.getAlertDialogMsg(), viewModel.shouldExit());
        }
        if (viewModel.isCancelDialogShowing()) {
            createCancelDialog();
        }
        super.onResume();
    }

    @Override
    protected void onPause() {
        if (alertDialog != null && alertDialog.isShowing()) {
            alertDialog.dismiss();
        }
        super.onPause();
    }

    public boolean isLocalFormSuperseded(String formId) {
        if (formId == null) {
            Timber.e("isLocalFormSuperseded: server is not OpenRosa-compliant. <formID> is null!");
            return true;
        }

        try (Cursor formCursor = formsDao.getFormsCursorForFormId(formId)) {
            return formCursor != null && formCursor.getCount() == 0 // form does not already exist locally
                    || viewModel.getFormDetailsByFormId().get(formId).isUpdated(); // or a newer version of this form is available
        }
    }

    /**
     * Causes any local forms that have been updated on the server to become checked in the list.
     * This is a prompt and a
     * convenience to users to download the latest version of those forms from the server.
     */
    private void selectSupersededForms() {

        ListView ls = listView;
        for (int idx = 0; idx < filteredFormList.size(); idx++) {
            HashMap<String, String> item = filteredFormList.get(idx);
            if (isLocalFormSuperseded(item.get(FORM_ID_KEY))) {
                ls.setItemChecked(idx, true);
                viewModel.addSelectedFormId(item.get(FORMDETAIL_KEY));
            }
        }
    }

    @Override
    public void formListDownloadingComplete(HashMap<String, ServerFormDetails> formList, FormApiException exception) {
        DialogUtils.dismissDialog(RefreshFormListDialogFragment.class, getSupportFragmentManager());
        downloadFormListTask.setDownloaderListener(null);
        downloadFormListTask = null;

        if (exception == null) {
            // Everything worked. Clear the list and add the results.
            viewModel.setFormDetailsByFormId(formList);
            viewModel.clearFormList();

            ArrayList<String> ids = new ArrayList<>(viewModel.getFormDetailsByFormId().keySet());
            for (int i = 0; i < formList.size(); i++) {
                String formDetailsKey = ids.get(i);
                ServerFormDetails details = viewModel.getFormDetailsByFormId().get(formDetailsKey);

                if (!displayOnlyUpdatedForms || details.isUpdated()) {
                    HashMap<String, String> item = new HashMap<>();
                    item.put(FORMNAME, details.getFormName());
                    item.put(FORMID_DISPLAY,
                            ((details.getFormVersion() == null) ? "" : (getString(R.string.version) + " "
                                    + details.getFormVersion() + " ")) + "ID: " + details.getFormId());
                    item.put(FORMDETAIL_KEY, formDetailsKey);
                    item.put(FORM_ID_KEY, details.getFormId());
                    item.put(FORM_VERSION_KEY, details.getFormVersion());

                    // Insert the new form in alphabetical order.
                    if (viewModel.getFormList().isEmpty()) {
                        viewModel.addForm(item);
                    } else {
                        int j;
                        for (j = 0; j < viewModel.getFormList().size(); j++) {
                            HashMap<String, String> compareMe = viewModel.getFormList().get(j);
                            String name = compareMe.get(FORMNAME);
                            if (name.compareTo(viewModel.getFormDetailsByFormId().get(ids.get(i)).getFormName()) > 0) {
                                break;
                            }
                        }
                        viewModel.addForm(j, item);
                    }
                }
            }

            filteredFormList.addAll(viewModel.getFormList());
            updateAdapter();
            selectSupersededForms();
            downloadButton.setEnabled(listView.getCheckedItemCount() > 0);
            toggleButton.setEnabled(listView.getCount() > 0);
            toggleButtonLabel(toggleButton, listView);

            if (viewModel.isDownloadOnlyMode()) {
                performDownloadModeDownload();
            }
        } else {
            switch (exception.getType()) {
                case FETCH_ERROR:
                case UNKNOWN_HOST:
                    String dialogMessage = new FormApiExceptionMapper(getResources()).getMessage(exception);
                    String dialogTitle = getString(R.string.load_remote_form_error);

                    if (viewModel.isDownloadOnlyMode()) {
                        setReturnResult(false, getString(R.string.load_remote_form_error), viewModel.getFormResults());
                    }

                    createAlertDialog(dialogTitle, dialogMessage, DO_NOT_EXIT);
                    break;

                case AUTH_REQUIRED:
                    createAuthDialog();
                    break;
            }
        }

    }

    private void performDownloadModeDownload() {
        //1. First check if all form IDS could be found on the server - Register forms that could not be found

        for (String formId : viewModel.getFormIdsToDownload()) {
            viewModel.putFormResult(formId, false);
        }

        ArrayList<ServerFormDetails> filesToDownload = new ArrayList<>();

        for (ServerFormDetails serverFormDetails : viewModel.getFormDetailsByFormId().values()) {
            String formId = serverFormDetails.getFormId();

            if (viewModel.getFormResults().containsKey(formId)) {
                filesToDownload.add(serverFormDetails);
            }
        }

        //2. Select forms and start downloading
        if (!filesToDownload.isEmpty()) {
            startFormsDownload(filesToDownload);
        } else {
            // None of the forms was found
            setReturnResult(false, "Forms not found on server", viewModel.getFormResults());
            finish();
        }
    }

    /**
     * Creates an alert dialog with the given tite and message. If shouldExit is set to true, the
     * activity will exit when the user clicks "ok".
     */
    private void createAlertDialog(String title, String message, final boolean shouldExit) {
        alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle(title);
        alertDialog.setMessage(message);
        DialogInterface.OnClickListener quitListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                switch (i) {
                    case DialogInterface.BUTTON_POSITIVE: // ok
                        // just close the dialog
                        viewModel.setAlertShowing(false);
                        // successful download, so quit
                        // Also quit if in download_mode only(called by another app/activity just to download)
                        if (shouldExit || viewModel.isDownloadOnlyMode()) {
                            finish();
                        }
                        break;
                }
            }
        };
        alertDialog.setCancelable(false);
        alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.ok), quitListener);
        alertDialog.setIcon(android.R.drawable.ic_dialog_info);
        viewModel.setAlertDialogMsg(message);
        viewModel.setAlertTitle(title);
        viewModel.setAlertShowing(true);
        viewModel.setShouldExit(shouldExit);
        DialogUtils.showDialog(alertDialog, this);
    }

    private void createAuthDialog() {
        viewModel.setAlertShowing(false);

        AuthDialogUtility authDialogUtility = new AuthDialogUtility();
        if (viewModel.getUrl() != null && viewModel.getUsername() != null && viewModel.getPassword() != null) {
            authDialogUtility.setCustomUsername(viewModel.getUsername());
            authDialogUtility.setCustomPassword(viewModel.getPassword());
        }
        DialogUtils.showDialog(authDialogUtility.createDialog(this, this, viewModel.getUrl()), this);
    }

    private void createCancelDialog() {
        cancelDialog = new ProgressDialog(this);
        cancelDialog.setTitle(getString(R.string.canceling));
        cancelDialog.setMessage(getString(R.string.please_wait));
        cancelDialog.setIcon(android.R.drawable.ic_dialog_info);
        cancelDialog.setIndeterminate(true);
        cancelDialog.setCancelable(false);
        viewModel.setCancelDialogShowing(true);
        DialogUtils.showDialog(cancelDialog, this);
    }

    @Override
    public void progressUpdate(String currentFile, int progress, int total) {
        RefreshFormListDialogFragment fragment = (RefreshFormListDialogFragment) getSupportFragmentManager()
                .findFragmentByTag(RefreshFormListDialogFragment.class.getName());
        fragment.setMessage(getString(R.string.fetching_file, currentFile,
                String.valueOf(progress), String.valueOf(total)));
    }

    @Override
    public void formsDownloadingComplete(HashMap<ServerFormDetails, String> result) {
        if (downloadFormsTask != null) {
            downloadFormsTask.setDownloaderListener(null);
        }

        cleanUpWebCredentials();

        DialogUtils.dismissDialog(RefreshFormListDialogFragment.class, getSupportFragmentManager());
        createAlertDialog(getString(R.string.download_forms_result), getDownloadResultMessage(result), EXIT);

        // Set result to true for forms which were downloaded
        if (viewModel.isDownloadOnlyMode()) {
            for (ServerFormDetails serverFormDetails : result.keySet()) {
                String successKey = result.get(serverFormDetails);
                if (Collect.getInstance().getString(R.string.success).equals(successKey)) {
                    if (viewModel.getFormResults().containsKey(serverFormDetails.getFormId())) {
                        viewModel.putFormResult(serverFormDetails.getFormId(), true);
                    }
                }
            }

            setReturnResult(true, null, viewModel.getFormResults());
        }
    }

    public static String getDownloadResultMessage(HashMap<ServerFormDetails, String> result) {
        Set<ServerFormDetails> keys = result.keySet();
        StringBuilder b = new StringBuilder();
        for (ServerFormDetails k : keys) {
            b.append(k.getFormName() + " ("
                    + ((k.getFormVersion() != null)
                    ? (Collect.getInstance().getString(R.string.version) + ": " + k.getFormVersion() + " ")
                    : "") + "ID: " + k.getFormId() + ") - " + result.get(k));
            b.append("\n\n");
        }

        return b.toString().trim();
    }

    @Override
    public void formsDownloadingCancelled() {
        if (downloadFormsTask != null) {
            downloadFormsTask.setDownloaderListener(null);
            downloadFormsTask = null;
        }

        cleanUpWebCredentials();

        if (cancelDialog != null && cancelDialog.isShowing()) {
            cancelDialog.dismiss();
            viewModel.setCancelDialogShowing(false);
        }

        if (viewModel.isDownloadOnlyMode()) {
            setReturnResult(false, "Download cancelled", null);
            finish();
        }
    }

    @Override
    public void updatedCredentials() {
        // If the user updated the custom credentials using the dialog, let us update our
        // variables holding the custom credentials
        if (viewModel.getUrl() != null) {
            HttpCredentialsInterface httpCredentials = webCredentialsUtils.getCredentials(URI.create(viewModel.getUrl()));

            if (httpCredentials != null) {
                viewModel.setUsername(httpCredentials.getUsername());
                viewModel.setPassword(httpCredentials.getPassword());
            }
        }

        downloadFormList();
    }

    @Override
    public void cancelledUpdatingCredentials() {
        finish();
    }

    private void setReturnResult(boolean successful, @Nullable String message, @Nullable HashMap<String, Boolean> resultFormIds) {
        Intent intent = new Intent();
        intent.putExtra(ApplicationConstants.BundleKeys.SUCCESS_KEY, successful);
        if (message != null) {
            intent.putExtra(ApplicationConstants.BundleKeys.MESSAGE, message);
        }
        if (resultFormIds != null) {
            intent.putExtra(ApplicationConstants.BundleKeys.FORM_IDS, resultFormIds);
        }

        setResult(RESULT_OK, intent);
    }

    private void cleanUpWebCredentials() {
        if (viewModel.getUrl() != null) {
            String host = Uri.parse(viewModel.getUrl())
                    .getHost();

            if (host != null) {
                webCredentialsUtils.clearCredentials(viewModel.getUrl());
            }
        }
    }

    @Override
    public void onCancelFormLoading() {
        if (downloadFormListTask != null) {
            downloadFormListTask.setDownloaderListener(null);
            downloadFormListTask.cancel(true);
            downloadFormListTask = null;

            // Only explicitly exit if DownloadFormListTask is running since
            // DownloadFormTask has a callback when cancelled and has code to handle
            // cancellation when in download mode only
            if (viewModel.isDownloadOnlyMode()) {
                setReturnResult(false, "User cancelled the operation", viewModel.getFormResults());
                finish();
            }
        }

        if (downloadFormsTask != null) {
            createCancelDialog();
            downloadFormsTask.cancel(true);
        }
        viewModel.setLoadingCanceled(true);
    }
}
