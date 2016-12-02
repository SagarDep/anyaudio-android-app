package any.audio.Activity;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.design.widget.BottomSheetBehavior;
import android.support.design.widget.Snackbar;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.text.Html;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.arlib.floatingsearchview.FloatingSearchView;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.extractor.ExtractorSampleSource;
import com.google.android.exoplayer.upstream.Allocator;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.Util;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import any.audio.Config.AppConfig;
import any.audio.Centrals.CentralDataRepository;
import any.audio.Managers.FontManager;
import any.audio.Network.ConnectivityUtils;
import any.audio.Config.Constants;
import any.audio.Interfaces.FeatureRequestListener;
import any.audio.SharedPreferences.StreamSharedPref;
import any.audio.helpers.CircularImageTransformer;
import any.audio.helpers.FileNameReformatter;
import any.audio.helpers.L;
import any.audio.helpers.MusicStreamer;
import any.audio.R;
import any.audio.Adapters.ResulstsRecyclerAdapter;
import any.audio.Models.ResultMessageObjectModel;
import any.audio.Models.SearchSuggestion;
import any.audio.helpers.SearchSuggestionHelper;
import any.audio.Models.SectionModel;
import any.audio.SharedPreferences.SharedPrefrenceUtils;
import any.audio.Fragments.StreamFragment;
import any.audio.Models.StreamMessageObjectModel;
import any.audio.Interfaces.StreamPrepareFailedListener;
import any.audio.helpers.TaskHandler;
import any.audio.services.UpdateCheckService;

public class Home extends AppCompatActivity {

    private static WeakReference<Home> wrActivity = null;

    private static final int MAX_DATABASE_RESPONSE_TIME = 5 * 1000; // 5 secs

    private ProgressBar indeterminateProgressBar;
    private ImageView streamingThumbnail;
    private TextView streamDuration;
    private SeekBar seekbar;
    private TextView currentStreamPosition;
    private TextView playPauseStreamBtn;
    private TextView streamingSongTitle;
    private RecyclerView mRecyclerView;
    private StaggeredGridLayoutManager layoutManager;
    private ResulstsRecyclerAdapter mRecyclerAdapter;
    private CentralDataRepository repository;
    private FloatingSearchView searchView;
    private SwipeRefreshLayout swipeRefressLayout;
    // non-static handlers : We don`t have
    private Handler mCDRMessageHandler;

    private SharedPrefrenceUtils utils;
    private ProgressBar progressBar;
    private TextView progressBarMsgPanel;
    private StreamUriBroadcastReceiver receiver;
    private boolean mReceiverRegistered = false;
    private MusicGenieMediaPlayer mPlayerThread;
    private static ExoPlayer exoPlayer;
    int mBuffered = -1;
    private boolean mStreamUpdateReceiverRegistered = false;
    private StreamProgressUpdateBroadcastReceiver streamProgressUpdateReceiver;
    private boolean streamBottomSheetsVisible = false;
    private View mBottomSheet;
    private BottomSheetBehavior mStreamingBottomSheetBehavior;
    private ProgressDialog progressDialog;
    private boolean isStreaming = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("AnyAudioApp", " [Home] onCreate()");
        setContentView(R.layout.new_home_test_layout);

        configureStorageDirectory(savedInstanceState);
        instantiateViews();
        handleMessages();   // message handler on UI thread
        subscribeToFeatureRequestListener();
        wrActivity = new WeakReference<Home>(this);
        utils = SharedPrefrenceUtils.getInstance(this);

        if (!utils.getFirstPageLoadedStatus()) {
            invokeAction(Constants.ACTION_TYPE_TRENDING);
            utils.setFirstPageLoadedStatus(true);
            // start only once

        } else {
            invokeAction(Constants.ACTION_TYPE_RESUME);
        }


    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!mReceiverRegistered) {
            registerForStreamUriFetchedBroadcastListen(this);
            L.m("Home", "Register Receiver");
        }
        if (!mStreamUpdateReceiverRegistered) {
            registerForStreamProgressUpdateBroadcastListen(this);

            if (StreamSharedPref.getInstance(this).getStreamState()) {
                Log.d("StreamingHome", " current state for Streaming is : True");
                prepareBottomStreamSheet();
                streamBottomSheetsVisible = true;
            }

            L.m("Home", "Register Receiver");
        }

    }

    @Override
    protected void onStop() {
        L.m("StreamingHome", "onStop()");
        super.onStop();

        if (mReceiverRegistered) {
            unRegisterStreamUriFetchBroadcast();
            L.m("HomeStream", "UnRegister Receiver");
        }

        if (mStreamUpdateReceiverRegistered) {
            unRegisterStreamProgressUpdateBroadcast();
            L.m("HomeStream", "UnRegister Receiver");
        }

        // setting flag for streamBottomSheets visibility
        streamBottomSheetsVisible = false;

    }

    @Override
    protected void onPause() {
        L.m("StreamingHome", "onPause()");
        super.onPause();


    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        invokeAction(Constants.ACTION_TYPE_RESUME);
//
//        if (SharedPrefrenceUtils.getInstance(this).getCurrentStreamingItem().length() > 0) {
//            L.m("StreamTest", "transacting");
//            transactStreamFragment();
//        }
    }


    private void handleMessages() {
        mCDRMessageHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                // hide progress
                L.m("CDR", "handling Message[UI Thread]");

                if (swipeRefressLayout.isRefreshing()) {
                    L.m("Home", "Disabling Swipe Refresh Layout");
                    swipeRefressLayout.setRefreshing(false);
                    swipeRefressLayout.setEnabled(true);
                }

                hideProgress();
                ResultMessageObjectModel object = (ResultMessageObjectModel) msg.obj;
                SectionModel item = object.data;

                if (object.Status == Constants.MESSAGE_STATUS_OK) {

                    // check if item is empty
                    if (item.getList() != null) {
                        if (item.getList().size() == 0 && mRecyclerAdapter.getItemCount() == 0) {
                            // hide the recycler view and Show Message
                            mRecyclerView.setVisibility(RecyclerView.GONE);
                            progressBar.setVisibility(View.GONE);
                            progressBarMsgPanel.setVisibility(View.VISIBLE);
                            progressBarMsgPanel.setText("Troubling Getting Data......");

                        } else {

                            mRecyclerView.setVisibility(RecyclerView.VISIBLE);
                            progressBar.setVisibility(View.GONE);
                            progressBarMsgPanel.setVisibility(View.GONE);

                        }
                    }
                    // enque item regardless of cases
                    mRecyclerAdapter.enque(item);
                } else {
                    //TODO: Collect Unexpected Error From CDR(Central Data Repository)
                }
            }
        };
    }

    private Runnable resumeContentCheckTask = new Runnable() {
        @Override
        public void run() {
            if (mRecyclerView.getVisibility() != View.VISIBLE) {
                progressBar.setVisibility(View.INVISIBLE);
                progressBarMsgPanel.setVisibility(View.VISIBLE);
                progressBarMsgPanel.setText("No Cached Data . Plz Have Working Internet Connection.");
            }
        }
    };

    /**
     * @param actionType type of action to invoke
     */
    public void invokeAction(int actionType) {

        // regardless of any action Type
        // make Recycler View Invisible and progress bar visible
        mRecyclerView.setVisibility(RecyclerView.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBarMsgPanel.setVisibility(View.VISIBLE);

        repository = CentralDataRepository.getInstance(this);

        switch (actionType) {

            case Constants.ACTION_TYPE_TRENDING:

                if (!ConnectivityUtils.getInstance(this).isConnectedToNet()) {
                    mRecyclerView.setVisibility(RecyclerView.GONE);
                    progressBar.setVisibility(View.INVISIBLE);
                    progressBarMsgPanel.setVisibility(View.VISIBLE);
                    progressBarMsgPanel.setText("Troubling Getting Data. Check Your Working Internet Connection");
                    return;
                }

                progressBarMsgPanel.setText("Loading Trending....");
                repository.submitAction(CentralDataRepository.FLAG_FIRST_LOAD, mCDRMessageHandler);
                break;

            case Constants.ACTION_TYPE_RESUME:
                //showProgress("Presenting Your Items");
                progressBarMsgPanel.setText("Loading Cached Data....");
                repository.submitAction(CentralDataRepository.FLAG_RESTORE, mCDRMessageHandler);
                new Handler().postDelayed(resumeContentCheckTask, MAX_DATABASE_RESPONSE_TIME);
                break;

            case Constants.ACTION_TYPE_REFRESS:

                if (!ConnectivityUtils.getInstance(this).isConnectedToNet()) {
                    mRecyclerView.setVisibility(RecyclerView.GONE);
                    progressBar.setVisibility(View.INVISIBLE);
                    progressBarMsgPanel.setVisibility(View.VISIBLE);
                    progressBarMsgPanel.setText("Troubling Getting Data......\nCheck Your Working Data Connection");
                    return;
                }// or continue the same

                progressBarMsgPanel.setText("Refreshing Content....");

                repository.submitAction(CentralDataRepository.FLAG_REFRESS, mCDRMessageHandler);
                break;

            case Constants.ACTION_TYPE_SEARCH:
                //showProgress("Searching Item");

                if (!ConnectivityUtils.getInstance(this).isConnectedToNet()) {
                    mRecyclerView.setVisibility(RecyclerView.GONE);
                    progressBar.setVisibility(View.INVISIBLE);
                    progressBarMsgPanel.setVisibility(View.VISIBLE);
                    progressBarMsgPanel.setText("Can`t Search Your Item . No Connectivity !");
                    return;
                }// or continue the same

                String searchQuery = SharedPrefrenceUtils.getInstance(this).getLastSearchTerm();
                progressBarMsgPanel.setText("Searching For.. " + searchQuery);
                repository.submitAction(CentralDataRepository.FLAG_SEARCH, mCDRMessageHandler);
                break;
        }

    }

    private void hideProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
        }
    }

    private void subscribeToFeatureRequestListener() {
        ResulstsRecyclerAdapter.getInstance(this).setOnFeatureRequestListener(new FeatureRequestListener() {
            @Override
            public void onTaskTapped(String type, String video_id, String file_name) {

                switch (type) {

                    case Constants.FEATURE_DOWNLOAD:
                        showAlertForAssuringFeatureRequest(Constants.FEATURE_DOWNLOAD, file_name, video_id);
                        break;

                    case Constants.FEATURE_STREAM:
                        showAlertForAssuringFeatureRequest(Constants.FEATURE_STREAM, file_name, video_id);
                        break;

                }
            }
        });
    }

    MusicStreamer.OnStreamUriFetchedListener streamUriFetchedListener = new MusicStreamer.OnStreamUriFetchedListener() {
        @Override
        public void onUriAvailable(String uri) {
        }
    };

    private void showAlertForAssuringFeatureRequest(String forType, final String stuff, final String v_id) {

        switch (forType) {
            case Constants.FEATURE_STREAM:
                DialogInterface.OnClickListener streamDialogClickListener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case DialogInterface.BUTTON_POSITIVE:

                                onYesStreamRequested(v_id, stuff);

                                break;

                            case DialogInterface.BUTTON_NEGATIVE:
                                //Dismiss dialog
                                dialog.dismiss();
                                break;
                        }
                    }
                };

                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Stream");
                builder.setMessage(stuff).setPositiveButton("Yes", streamDialogClickListener)
                        .setNegativeButton("No", streamDialogClickListener).show();

                break;
            case Constants.FEATURE_DOWNLOAD:
                DialogInterface.OnClickListener downloaDialogClickListener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case DialogInterface.BUTTON_POSITIVE:

                                if(!ConnectivityUtils.getInstance(Home.this).isConnectedToNet()){
                                    Snackbar.make(searchView, "Download ! No Internet Connection ", Snackbar.LENGTH_LONG).show();
                                    }else{
                                if (!checkForExistingFile(stuff)) {

                                    TaskHandler
                                            .getInstance(Home.this)
                                            .addTask(stuff, v_id);

                                    Toast.makeText(Home.this, " Added " + stuff + " To Download", Toast.LENGTH_LONG).show();

                                } else {

                                    DialogInterface.OnClickListener reDownloadTaskAlertDialog = new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {

                                            switch (which) {
                                                case DialogInterface.BUTTON_POSITIVE:

                                                    TaskHandler
                                                            .getInstance(Home.this)
                                                            .addTask(stuff, v_id);

                                                    Toast.makeText(Home.this, " Added " + stuff + " To Download", Toast.LENGTH_LONG).show();
                                                    break;

                                                case DialogInterface.BUTTON_NEGATIVE:
                                                    //dismiss dialog
                                                    dialog.dismiss();
                                                    break;
                                            }

                                        }
                                    };


                                    AlertDialog.Builder builderReDownloadAlert = new AlertDialog.Builder(Home.this);
                                    builderReDownloadAlert.setTitle("File Already Exists !!! ");
                                    builderReDownloadAlert.
                                            setMessage(stuff)
                                            .setPositiveButton("Re-Download", reDownloadTaskAlertDialog)
                                            .setNegativeButton("Cancel", reDownloadTaskAlertDialog).show();

                                }
                                }

                                break;

                            case DialogInterface.BUTTON_NEGATIVE:
                                //dismiss dialog
                                dialog.dismiss();
                                break;
                        }
                    }
                };

                AlertDialog.Builder builderDownloadAlert = new AlertDialog.Builder(this);
                builderDownloadAlert.setTitle("Download");
                builderDownloadAlert.setMessage(stuff).setPositiveButton("Yes", downloaDialogClickListener)
                        .setNegativeButton("No", downloaDialogClickListener).show();
        }


    }

    private void onYesStreamRequested(String v_id, String stuff) {        // called after user confirms pop-up

        /* WID: after confirmation of user
                check working internet connection and then perform the action.
                if true:
                reset the player if already playing another songs
                > set Streaming State to true
                > hide bottom Sheets if already visible
                > show bottomSheet with indet-progress bar
                > Init StreamUri Fetch Process


                Done !!

         */


        if (ConnectivityUtils.getInstance(this).isConnectedToNet()) {

            Log.d("StreamingHome", " Resetting Player");
            resetPlayer();

            Log.d("Home", "setting stream state to true");
            StreamSharedPref.getInstance(this).setStreamState(true);

            if (!streamBottomSheetsVisible) {
                Log.d("StreamingHome", "debut initing streaming view");
                prepareBottomStreamSheet();
                streamBottomSheetsVisible = true;
            } else {
                Log.d("StreamingHome", "disabling and re-initing streaming view");
                mStreamingBottomSheetBehavior.setPeekHeight(0);
                prepareBottomStreamSheet();
            }


            MusicStreamer
                    .getInstance(Home.this)
                    .setData(v_id, stuff)
                    .setOnStreamUriFetchedListener(streamUriFetchedListener)
                    .initProcess();

        } else {
            Snackbar.make(searchView, "Stream ! No Internet Connection ", Snackbar.LENGTH_LONG).show();
        }

    }

    private int screenMode() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        float yInches = metrics.heightPixels / metrics.ydpi;
        float xInches = metrics.widthPixels / metrics.xdpi;

        double diagonal = Math.sqrt(yInches * yInches + xInches * xInches);
        if (diagonal > 6.5) {
            return Constants.SCREEN_MODE_TABLET;
        } else {
            return Constants.SCREEN_MODE_MOBILE;
        }
    }


    public boolean checkForExistingFile(String fileNameToCheck) {
        // assumes that fileNameToCheck is reformatted

       // fileNameToCheck = FileNameReformatter.getInstance(this).getFormattedName(fileNameToCheck) + ".m4a";

        File dir = new File(Constants.FILES_DIR);
        File[] _files = dir.listFiles();

        for (File f : _files) {
            Log.d("HomeFileDuplicate", " checking " + (f.toString().substring(f.toString().lastIndexOf("/") + 1)) + " against " + fileNameToCheck);
            if ((f.toString().substring(f.toString().lastIndexOf("/") + 1)).equals(fileNameToCheck))
                return true;
        }

        return false;
    }

    public String reformatFileName(String oldName) {

        String newName = "";
        // remove '|'
        newName += oldName.replaceAll("\\|", " ");
        newName = newName.replaceAll("\\,", "");
        newName = newName.replaceAll("\\-", "");

        return newName;
    }

    private void plugAdapter() {
        mRecyclerAdapter.setOrientation(getOrientation());
        mRecyclerAdapter.setScreenMode(screenMode());
        mRecyclerView.setAdapter(mRecyclerAdapter);
    }

    private void instantiateViews() {

        int maxCols = (isPortrait(getOrientation())) ? ((screenMode() == Constants.SCREEN_MODE_MOBILE) ? 2 : 3) : 4;
        swipeRefressLayout = (SwipeRefreshLayout) findViewById(R.id.content_refresser);
        mRecyclerView = (RecyclerView) findViewById(R.id.trendingRecylerView);
        layoutManager = new StaggeredGridLayoutManager(maxCols, 1);
        mRecyclerAdapter = ResulstsRecyclerAdapter.getInstance(this);
        mRecyclerView.setLayoutManager(layoutManager);
        progressBar = (ProgressBar) findViewById(R.id.homeProgressBar);
        progressBarMsgPanel = (TextView) findViewById(R.id.ProgressMsgPanel);
        swipeRefressLayout.setColorSchemeColors(getResources().getColor(R.color.PrimaryColor), Color.WHITE);
        swipeRefressLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                swipeRefressLayout.setRefreshing(true);
                refressContent();
            }
        });

        plugAdapter();
        setSearchView();
    }

    private boolean isPortrait(int orientation) {
        return orientation % 2 == 0;
    }

    private void refressContent() {

        if (ConnectivityUtils.getInstance(this).isConnectedToNet()) {
            swipeRefressLayout.setEnabled(false);
            invokeAction(Constants.ACTION_TYPE_REFRESS);


        } else {
            Snackbar.make(swipeRefressLayout, "No Connectivity !!", Snackbar.LENGTH_SHORT).show();
            swipeRefressLayout.setRefreshing(false);
            swipeRefressLayout.setVisibility(View.VISIBLE);
        }

    }

    public void setSearchView() {
        searchView = (FloatingSearchView) findViewById(R.id.floating_search_view);
        searchView.setOnQueryChangeListener(new FloatingSearchView.OnQueryChangeListener() {


            @Override
            public void onSearchTextChanged(String oldText, String newText) {


                if (!ConnectivityUtils.getInstance(Home.this).isConnectedToNet()) {
                    mRecyclerView.setVisibility(RecyclerView.GONE);
                    progressBar.setVisibility(View.INVISIBLE);
                    progressBarMsgPanel.setVisibility(View.VISIBLE);
                    progressBarMsgPanel.setText("Can`t Suggest. No Connectivity");
                    return;
                } else {
                    progressBarMsgPanel.setText("");
                }

                if (!oldText.equals("") && newText.equals("")) {
                    searchView.clearSuggestions();
                } else {

                    searchView.showProgress();
                    SearchSuggestionHelper.getInstance(Home.this).findSuggestion(newText,
                            new SearchSuggestionHelper.OnFindSuggestionListener() {
                                @Override
                                public void onResult(ArrayList<SearchSuggestion> list) {
                                    searchView.swapSuggestions(list);
                                    searchView.hideProgress();
                                }

                                @Override
                                public void onCacelRequests() {
                                    searchView.hideProgress();
                                    searchView.clearSuggestions();
                                }
                            }
                    );
                }


            }


        });


        searchView.setOnSearchListener(new FloatingSearchView.OnSearchListener() {
            @Override
            public void onSuggestionClicked(com.arlib.floatingsearchview.suggestions.model.SearchSuggestion searchSuggestion) {
                //stop futhur suggestion requests

                SearchSuggestionHelper.getInstance(Home.this).cancelFuthurRequestUntilQueryChange();

                fireSearch(searchSuggestion.getBody());
            }

            @Override
            public void onSearchAction(String query) {
                fireSearch(query);
            }
        });

        searchView.setOnMenuItemClickListener(new FloatingSearchView.OnMenuItemClickListener() {
            @Override
            public void onActionMenuItemSelected(MenuItem menuItem) {
                int id = menuItem.getItemId();

                if (id == R.id.action_settings) {

                    Intent i = new Intent(Home.this, UserPreferenceSetting.class);

                    startActivity(i);

                }
                if (id == R.id.action_downloads) {

                    Intent i = new Intent(Home.this, DowloadsActivity.class);
                    boolean willStreamingContinue = StreamSharedPref.getInstance(Home.this).getStreamState();
                    Log.d("StreamingHome", " Will Streaming Continue " + willStreamingContinue);
                    i.putExtra(Constants.EXTRAA_FLAG_STREAM_WILL_CONTINUE, willStreamingContinue);
                    startActivity(i);


                }

                if (id == R.id.action_request_trending) {
                    invokeAction(Constants.ACTION_TYPE_TRENDING);
                }

            }
        });


    }

    private void fireSearch(String query) {

        SharedPrefrenceUtils.getInstance(this).setLastSearchTerm(query);
        L.m("Home", " invoking action search");
        // cancelling furthur suggestion    -------------- User is Self-Satified
        SearchSuggestionHelper.getInstance(this).cancelFuthurRequestUntilQueryChange();
        invokeAction(Constants.ACTION_TYPE_SEARCH);

    }

    private void configureStorageDirectory(Bundle savedInstance) {

        if (savedInstance == null) {
            L.m("Home configureStorageDirectory()", "making dirs");
            AppConfig.getInstance(this).configureDevice();
        }
    }

    private int getOrientation() {
        return getWindowManager().getDefaultDisplay().getOrientation();
    }

    private void unRegisterStreamUriFetchBroadcast() {
        if (mReceiverRegistered) {
            this.unregisterReceiver(receiver);
            mReceiverRegistered = false;
        }
    }

    private void registerForStreamUriFetchedBroadcastListen(Context context) {
        receiver = new StreamUriBroadcastReceiver();
        if (!mReceiverRegistered) {
            context.registerReceiver(receiver, new IntentFilter(Constants.ACTION_STREAM_URL_FETCHED));
            mReceiverRegistered = true;
        }
    }

    public class StreamUriBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getAction().equals(Constants.ACTION_STREAM_URL_FETCHED)) {

                L.m("Home", "update via broadcast: streaming uri " + intent.getStringExtra(Constants.EXTRAA_URI));
                StreamSharedPref.getInstance(Home.this).setStreamUrlFetchedStatus(true);

                if (!isStreaming) {

                    String uri = intent.getStringExtra(Constants.EXTRAA_URI);

                    if (uri.equals(Constants.STREAM_PREPARE_FAILED_URL_FLAG)) {
                        // WID: close the bottomSheets with a toast error
                        hideStreamSheet("Something is Wrong !! Please Try Again.");
                        return;
                    }


                    mPlayerThread = new MusicGenieMediaPlayer(Home.this, uri);
                    mPlayerThread.start();

                }

            }
        }

    }

    private void hideStreamSheet(String msg) {

        try {
            mStreamingBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } catch (Exception e) {
            Log.d("StreamingHome", "Stream Url Not Fetched");
        }

        StreamSharedPref.getInstance(Home.this).setStreamState(false);
        Toast.makeText(Home.this, msg, Toast.LENGTH_LONG).show();

    }

    private void unRegisterStreamProgressUpdateBroadcast() {
        if (mStreamUpdateReceiverRegistered) {
            this.unregisterReceiver(streamProgressUpdateReceiver);
            mStreamUpdateReceiverRegistered = false;
        }
    }

    private void registerForStreamProgressUpdateBroadcastListen(Context context) {
        streamProgressUpdateReceiver = new StreamProgressUpdateBroadcastReceiver();
        if (!mStreamUpdateReceiverRegistered) {
            context.registerReceiver(streamProgressUpdateReceiver, new IntentFilter(Constants.ACTION_STREAM_PROGRESS_UPDATE_BROADCAST));
            mStreamUpdateReceiverRegistered = true;
        }
    }

    public class StreamProgressUpdateBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getAction().equals(Constants.ACTION_STREAM_PROGRESS_UPDATE_BROADCAST)) {

                int contentLen = Integer.parseInt(intent.getStringExtra(Constants.EXTRAA_STREAM_CONTENT_LEN));
                int buffered = Integer.parseInt(intent.getStringExtra(Constants.EXTRAA_STREAM_BUFFERED_PROGRESS));
                int progress = Integer.parseInt(intent.getStringExtra(Constants.EXTRAA_STREAM_PROGRESS));

//                if (!streamBottomSheetsVisible) {
//                    prepareBottomStreamSheet();
//                    streamBottomSheetsVisible = true;
//                }

                if (streamBottomSheetsVisible) {

                    if (contentLen > 0) {

                        try {

                            if (contentLen > 0 && buffered > 0) {

                                indeterminateProgressBar.setVisibility(View.INVISIBLE);
                                seekbar.setVisibility(View.VISIBLE);
                                playPauseStreamBtn.setVisibility(View.VISIBLE);

                            }

                            currentStreamPosition.setText(getTimeFromMillisecond(progress));
                            seekbar.setProgress(progress);
                            streamDuration.setText(" |  " + getTimeFromMillisecond(contentLen));
                            seekbar.setMax(contentLen);

                            if (mBuffered < buffered) {
                                seekbar.setSecondaryProgress(buffered);
                                mBuffered = buffered;
                            }

                        } catch (Exception e) {
                            Log.d("StreamFragment", "something went wrong " + e);
                        }
                    }
                }

                //WTD: hide stream bar when player reached last of track
                if (contentLen <= progress && contentLen >= 0) {
                    hideStreamSheet("Thank You");
                }


            }
        }

    }

    private String getTimeFromMillisecond(int millis) {
        String hr = "";
        String min = "";
        String sec = "";
        String time = "";
        int i_hr = (millis / 1000) / 3600;
        int i_min = (millis / 1000) / 60;
        int i_sec = (millis / 1000) % 60;

        if (i_hr == 0) {
            min = (String.valueOf(i_min).length() < 2) ? "0" + i_min : String.valueOf(i_min);
            sec = (String.valueOf(i_sec).length() < 2) ? "0" + i_sec : String.valueOf(i_sec);
            time = min + " : " + sec;
        } else {
            hr = (String.valueOf(i_hr).length() < 2) ? "0" + i_hr : String.valueOf(i_hr);
            min = (String.valueOf(i_min).length() < 2) ? "0" + i_min : String.valueOf(i_min);
            sec = (String.valueOf(i_sec).length() < 2) ? "0" + i_sec : String.valueOf(i_sec);
            time = hr + " : " + min + " : " + sec;
        }

        // Log.d("StreamingHome"," time returned "+time);

        return time;
    }

    public void broadcastStreamProgresUpdate(String playingAt, String contentLen, String bufferedProgress) {

        Intent intent = new Intent(Constants.ACTION_STREAM_PROGRESS_UPDATE_BROADCAST);
        intent.putExtra(Constants.EXTRAA_STREAM_PROGRESS, playingAt);
        intent.putExtra(Constants.EXTRAA_STREAM_CONTENT_LEN, contentLen);
        intent.putExtra(Constants.EXTRAA_STREAM_BUFFERED_PROGRESS, bufferedProgress);
        sendBroadcast(intent);

    }

    public void prepareBottomStreamSheet() {

        final String playBtn = getString(R.string.streaming_play_btn);
        final String pauseBtn = getString(R.string.streaming_pause_btn);
        mBottomSheet = findViewById(R.id.design_bottom_sheet);
        mStreamingBottomSheetBehavior = BottomSheetBehavior.from(mBottomSheet);
        mStreamingBottomSheetBehavior.setPeekHeight(10);
        mStreamingBottomSheetBehavior.setBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                switch (newState) {
                    case BottomSheetBehavior.STATE_DRAGGING:
                        Log.i("BottomSheetCallback", "BottomSheetBehavior.STATE_DRAGGING");
                        break;
                    case BottomSheetBehavior.STATE_SETTLING:
                        Log.i("BottomSheetCallback", "BottomSheetBehavior.STATE_SETTLING");
                        break;
                    case BottomSheetBehavior.STATE_EXPANDED:
                        Log.i("BottomSheetCallback", "BottomSheetBehavior.STATE_EXPANDED");
                        break;
                    case BottomSheetBehavior.STATE_COLLAPSED:

                        if (StreamSharedPref.getInstance(Home.this).getStreamUrlFetchedStatus()) {

                            streamBottomSheetsVisible = false;
                            resetPlayer();
                            StreamSharedPref.getInstance(Home.this).setStreamUrlFetchedStatus(false);

                        } else {

                            Toast.makeText(Home.this, "Cannot Cancel At The Moment !", Toast.LENGTH_SHORT).show();
                            mStreamingBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);

                        }

                        Log.i("BottomSheetCallback", "BottomSheetBehavior.STATE_COLLAPSED");
                        break;
                    case BottomSheetBehavior.STATE_HIDDEN:

                        if (StreamSharedPref.getInstance(Home.this).getStreamUrlFetchedStatus()) {
                            streamBottomSheetsVisible = false;
                            resetPlayer();
                            StreamSharedPref.getInstance(Home.this).setStreamUrlFetchedStatus(false);

                        } else {
                            Toast.makeText(Home.this, "Cannot Cancel At The Moment !", Toast.LENGTH_SHORT).show();
                            mStreamingBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                        }

                        Log.i("BottomSheetCallback", "BottomSheetBehavior.STATE_HIDDEN");
                        break;
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                Log.i("BottomSheetCallback", "slideOffset: " + slideOffset);
            }
        });


        streamingThumbnail = (ImageView) findViewById(R.id.streaming_item_thumb);
        streamDuration = (TextView) findViewById(R.id.streaming_item_totalTrackLengthText);
        seekbar = (SeekBar) findViewById(R.id.streaming_item_audio_seekbar);
        currentStreamPosition = (TextView) findViewById(R.id.streaming_item_currentTrackPositionText);
        playPauseStreamBtn = (TextView) findViewById(R.id.streaming_item_play_pauseBtn);
        playPauseStreamBtn.setVisibility(View.GONE);
        streamingSongTitle = (TextView) findViewById(R.id.streaming_item_title);
        indeterminateProgressBar = (ProgressBar) findViewById(R.id.stream_indeterminate_progress);

        // reset progress
        seekbar.setVisibility(View.INVISIBLE);
        indeterminateProgressBar.setVisibility(View.VISIBLE);
        Log.d("StreamingHome", " reset the visibility of progress");

        mStreamingBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        String uri = StreamSharedPref.getInstance(Home.this).getStreamThumbnailUrl();
        String streamFileName = StreamSharedPref.getInstance(Home.this).getStreamTitle();

        Log.d("StreamingHome", " thumb from " + uri);
        Picasso.with(Home.this).load(uri).transform(new CircularImageTransformer()).into(streamingThumbnail);
        streamingSongTitle.setText(streamFileName);
        streamDuration.setText(" | 00:00");
        currentStreamPosition.setText("00:00");
        seekbar.setProgress(0);
        seekbar.setSecondaryProgress(0);

        FontManager mFontManager = FontManager.getInstance(Home.this);
        Typeface mTypefaceMaterial = mFontManager.getTypeFace(FontManager.FONT_MATERIAL);
        Typeface mTypefaceRaleway = mFontManager.getTypeFace(FontManager.FONT_RALEWAY_REGULAR);
        // raleway
        streamDuration.setTypeface(mTypefaceRaleway);
        currentStreamPosition.setTypeface(mTypefaceRaleway);
        streamingSongTitle.setTypeface(mTypefaceRaleway);
        // material icons
        playPauseStreamBtn.setTypeface(mTypefaceMaterial);

        seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int position, boolean fromUser) {

                if (fromUser) {
                    if (mPlayerThread != null) {
                        L.m("Home", "sending seek msg");
                        if (exoPlayer.getBufferedPosition() > position)
                            exoPlayer.seekTo(position);
                    }
                }

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        playPauseStreamBtn.setOnClickListener(new View.OnClickListener() {
                                                  @Override
                                                  public void onClick(View view) {

                                                      Log.d("StreamingHome", " clicked Play Pause Btn");

                                                      if (exoPlayer != null) {

                                                          if (StreamSharedPref.getInstance(Home.this).getStreamerPlayState()) {
                                                              // pause
                                                              StreamSharedPref.getInstance(Home.this).setStreamerPlayState(false);
                                                              playPauseStreamBtn.setText(playBtn);
                                                          } else {
                                                              //play
                                                              StreamSharedPref.getInstance(Home.this).setStreamerPlayState(true);
                                                              playPauseStreamBtn.setText(pauseBtn);
                                                          }

                                                          exoPlayer.setPlayWhenReady(StreamSharedPref.getInstance(Home.this).getStreamerPlayState());
                                                      } else {
                                                          Log.d("StreamingHome", " exoPlayer object null");
                                                      }
                                                  }


                                              }

        );

    }

    public class MusicGenieMediaPlayer extends Thread {

        private static final String TAG = "MusicGenieMediaPlayer";
        private Context context;
        private MusicGenieMediaPlayer mInstance;
        private MediaPlayer player;
        private static final int BUFFER_SEGMENT_SIZE = 64 * 1024;
        private static final int BUFFER_SEGMENT_COUNT = 256;
        private MediaCodecAudioTrackRenderer audioRenderer;
        private Uri mUri;
        private Handler mUIHandler;
        private boolean PLAYER_STATE_ENDED = false;
        private boolean PLAYER_STATE_PLAYING = false;
        private int playerCurrentPositon = -1;
        private int playerContentDuration = -1;

        public MusicGenieMediaPlayer(Context context, String uri) {
            this.context = context;
            mUri = Uri.parse(uri);
            // mUIHandler = handler;

        }


        @Override
        public void run() {
            Looper.prepare();
            useExoplayer();
            Looper.loop();
        }

        private void useNativeMediaPlayer() {

            Uri mUri = Uri.parse("https://redirector.googlevideo.com/videoplayback?ip=2405%3A204%3Aa108%3Ad941%3Ac1a6%3Aee19%3Ab91e%3A2212&requiressl=yes&lmt=1475255327003268&itag=43&id=o-AEwHFbPb9W4VvmStnHurqdnMuVo-XQif-0oAXbXuVoed&dur=0.000&pcm2cms=yes&source=youtube&upn=gA6OYhdD4fs&mime=video%2Fwebm&ratebypass=yes&ipbits=0&initcwndbps=320000&expire=1475706999&gcr=in&sparams=dur%2Cei%2Cgcr%2Cid%2Cinitcwndbps%2Cip%2Cipbits%2Citag%2Clmt%2Cmime%2Cmm%2Cmn%2Cms%2Cmv%2Cpcm2cms%2Cpl%2Cratebypass%2Crequiressl%2Csource%2Cupn%2Cexpire&key=yt6&mn=sn-gwpa-qxae&mm=31&ms=au&ei=Fyz1V4KYDImEoAPRwLjwCQ&pl=36&mv=m&mt=1475684495&signature=A8B6FD2BC32B05B17E0C62DA1E36967B72E84E3A.3515AE79C436E6A1B1A42BC9E3E14892C5C2C95A&title=%E0%A4%B2%E0%A4%B2%E0%A4%95%E0%A5%80+%E0%A4%9A%E0%A5%81%E0%A4%A8%E0%A4%B0%E0%A4%BF%E0%A4%AF%E0%A4%BE+%E0%A4%93%E0%A5%9D+%E0%A4%95%E0%A5%87+-+Pawan+Singh+%26+Akshara+Singh+-+Dular+Devi+Maiya+Ke+-+Bhojpuri+Devi+Geet+2016");
            MediaPlayer mediaPlayer = MediaPlayer.create(context, mUri);
            mediaPlayer.start();

            if (mediaPlayer != null) {
                while (mediaPlayer.isPlaying()) {

                    StreamMessageObjectModel objectModel = new StreamMessageObjectModel(
                            mediaPlayer.getCurrentPosition(),
                            mediaPlayer.getDuration(),
                            0);

                    Message msg = Message.obtain();
                    msg.obj = objectModel;
                    mUIHandler.sendMessage(msg);

                }
            }

        }

        private void resetExoPlayer() {

            // check for already streaming
            if (exoPlayer != null)
                if (exoPlayer.getPlayWhenReady()) {
                    exoPlayer.stop();
                    exoPlayer.release();
                }

        }

        private void useExoplayer() {

            // resetExoPlayer();
            exoPlayer = ExoPlayer.Factory.newInstance(1);
            // Settings for exoPlayer
            Allocator allocator = new DefaultAllocator(BUFFER_SEGMENT_SIZE);
            String userAgent = Util.getUserAgent(context, "AnyAudio");
            DataSource dataSource = new DefaultUriDataSource(context, null, userAgent);

            ExtractorSampleSource sampleSource = new ExtractorSampleSource(
                    mUri,
                    dataSource,
                    allocator,
                    BUFFER_SEGMENT_SIZE * BUFFER_SEGMENT_COUNT);

            audioRenderer = new MediaCodecAudioTrackRenderer(sampleSource);
            // Prepare ExoPlayer
            exoPlayer.prepare(audioRenderer);
            exoPlayer.setPlayWhenReady(true);
            StreamSharedPref.getInstance(context).setStreamerPlayState(true);
            exoPlayer.addListener(new ExoPlayer.Listener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {

                    Log.d("ExoPlayer", " Player State Changed PlayWhenReady:" + playWhenReady + " PlayerState : " + playbackState);
                    if (playbackState == 5) // 5 - > integer code for player end state
                        StreamSharedPref.getInstance(context).setStreamState(false);

                }

                @Override
                public void onPlayWhenReadyCommitted() {

                }

                @Override
                public void onPlayerError(ExoPlaybackException error) {
                    Log.d("ExoPlayer", "exo error setting stream state false");
                    StreamSharedPref.getInstance(Home.this).setStreamState(false);
                    hideStreamSheet("Something Is Wrong ! ");
                }
            });

            while (exoPlayer != null) {

                playerCurrentPositon = (int) exoPlayer.getCurrentPosition();
                playerContentDuration = (int) exoPlayer.getDuration();

                if (exoPlayer.getPlayWhenReady()) {
                    Log.d("ExoPlayer", " broadcasting progress");
                    broadcastStreamProgresUpdate(
                            String.valueOf(playerCurrentPositon),
                            String.valueOf(playerContentDuration),
                            String.valueOf(exoPlayer.getBufferedPosition())
                    );
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }


                if (playerContentDuration != -1) {
                    if (playerCurrentPositon >= playerContentDuration) break;
                }

            }


        }

    }

    private void resetPlayer() {

        if (exoPlayer != null) {


            exoPlayer.setPlayWhenReady(false);
            exoPlayer.stop();
            exoPlayer.release();
            streamBottomSheetsVisible = false;
            L.m("StreamingHome", "Player Reset Done");
        }
        StreamSharedPref.getInstance(Home.this).setStreamState(false);

    }


}