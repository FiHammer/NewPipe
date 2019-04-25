package org.schabi.newpipe.local.feed;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.ActionBar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.BaseListFragment;
import org.schabi.newpipe.report.UserAction;
import org.schabi.newpipe.local.subscription.SubscriptionService;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;

import io.reactivex.Flowable;
import io.reactivex.MaybeObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class FeedFragment extends BaseListFragment<List<SubscriptionEntity>, Void> {

    private static final int OFF_SCREEN_ITEMS_COUNT = 3;
    private static final int MIN_ITEMS_INITIAL_LOAD = 8;
    private int FEED_LOAD_COUNT = MIN_ITEMS_INITIAL_LOAD;

    private int subscriptionPoolSize;

    private ArrayList<StreamInfoItem> todayItems;
    private int num;
    private ArrayList<ChannelInfo> chachedChannel;
    private ArrayList<ArrayList<String>> usedChannels;
    private Integer thisLoad;
    /*
    * 0: 0      vor X Stunden
    * 1: 1      vor 1 Tag
    * 2: 2      vor 2 Tagen
    * ....      vor X Tagen
    * 6: 6      vor 6 Tagen
    * 7: 7-13   vor 1 Woche
    * 8: 14-20  vor 2 Wochen
    * 9: 21-27  vor 3 Wochen
    * 10: 28-X  vor 1 Monat
    * Monat not supportet
    * */

    private String before_1_sec = "vor 1 Sekunde";
    private String space_secs = " Sekunden";
    private String before_1_min = "vor 1 Minuten";
    private String space_mins = " Minute";
    private String before_1_hr = "vor 1 Stunde";
    private String space_hrs = " Stunden";

    private String[] loadParse = {"today", "vor 1 Tag", "vor 2 Tagen", "vor 3 Tagen", "vor 4 Tagen",
            "vor 5 Tagen", "vor 6 Tagen", "vor 1 Woche", "vor 2 Wochen", "vor 3 Wochen", "vor 1 Monat"};

    private SubscriptionService subscriptionService;

    private AtomicBoolean allItemsLoaded = new AtomicBoolean(false);
    private HashSet<String> itemsLoaded = new HashSet<>();
    private final AtomicInteger requestLoadedAtomic = new AtomicInteger();

    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Disposable subscriptionObserver;
    private Subscription feedSubscriber;

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d("onCreate", "start");
        super.onCreate(savedInstanceState);
        subscriptionService = SubscriptionService.getInstance(activity);
        usedChannels = new ArrayList<>();
        for (int x=0; x<11; x++) {
            usedChannels.add(new ArrayList<String>());
        }
        thisLoad = 0;
        chachedChannel = new ArrayList<>();
        todayItems = new ArrayList<>();

        FEED_LOAD_COUNT = howManyItemsToLoad();
        Log.d("onCreate", "end");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        Log.d("onCreateView", "start");
        if(!useAsFrontPage) {
            setTitle(activity.getString(R.string.fragment_whats_new));
        }
        Log.d("onCreateView", "end");
        return inflater.inflate(R.layout.fragment_feed, container, false);
    }

    @Override
    public void onPause() {
        Log.d("onPause", "start");
        super.onPause();
        disposeEverything();
        Log.d("onPause", "end");
    }

    @Override
    public void onResume() {
        Log.d("onResume", "start");
        super.onResume();
        if (wasLoading.get()) doInitialLoadLogic();
        Log.d("onResume", "end");
    }

    @Override
    public void onDestroy() {
        Log.d("onDestroy", "start");
        super.onDestroy();

        disposeEverything();
        subscriptionService = null;
        compositeDisposable = null;
        subscriptionObserver = null;
        feedSubscriber = null;
        Log.d("onDestroy", "end");
    }

    @Override
    public void onDestroyView() {
        // Do not monitor for updates when user is not viewing the feed fragment.
        // This is a waste of bandwidth.
        Log.d("onDestroyView", "start");
        disposeEverything();
        super.onDestroyView();
        Log.d("onDestroyView", "end");
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        Log.d("setUserVisibleHint", "start");
        super.setUserVisibleHint(isVisibleToUser);
        if (activity != null && isVisibleToUser) {
            setTitle(activity.getString(R.string.fragment_whats_new));
        }
        Log.d("setUserVisibleHint", "end");
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        Log.d("setUserVisibleHint", "start");
        super.onCreateOptionsMenu(menu, inflater);

        ActionBar supportActionBar = activity.getSupportActionBar();

        if(useAsFrontPage) {
            supportActionBar.setDisplayShowTitleEnabled(true);
            //supportActionBar.setDisplayShowTitleEnabled(false);
        }
        Log.d("onCreateOptionsMenu", "end");
    }

    @Override
    public void reloadContent() {
        Log.d("reloadContent", "start");
        resetFragment();
        super.reloadContent();
        Log.d("reloadContent", "end");
    }

    /*//////////////////////////////////////////////////////////////////////////
    // StateSaving
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void writeTo(Queue<Object> objectsToSave) {
        Log.d("writeTo", "start");
        super.writeTo(objectsToSave);
        objectsToSave.add(allItemsLoaded);
        objectsToSave.add(itemsLoaded);
        Log.d("writeTo", "end");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void readFrom(@NonNull Queue<Object> savedObjects) throws Exception {
        Log.d("readFrom", "start");
        super.readFrom(savedObjects);
        allItemsLoaded = (AtomicBoolean) savedObjects.poll();
        itemsLoaded = (HashSet<String>) savedObjects.poll();
        Log.d("readFrom", "end");
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Feed Loader
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void startLoading(boolean forceLoad) {
        Log.d("startLoading", "start");
        if (DEBUG) Log.d(TAG, "startLoading() called with: forceLoad = [" + forceLoad + "]");
        if (subscriptionObserver != null) subscriptionObserver.dispose();

        if (allItemsLoaded.get()) {
            if (infoListAdapter.getItemsList().size() == 0) {
                showEmptyState();
            } else {
                showListFooter(false);
                hideLoading();
            }

            isLoading.set(false);
            Log.d("startLoading", "end 0");
            return;
        }

        isLoading.set(true);
        showLoading();
        showListFooter(true);
        subscriptionObserver = subscriptionService.getSubscription()
                .onErrorReturnItem(Collections.emptyList())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handleResult, this::onError);

        Log.d("startLoading", "End 1");
    }

    @Override
    public void handleResult(@android.support.annotation.NonNull List<SubscriptionEntity> result) {
        Log.d("handleResult", "start");
        super.handleResult(result);
        if (result.isEmpty()) {
            infoListAdapter.clearStreamItemList();
            showEmptyState();
            return;
        }

        subscriptionPoolSize = result.size();
        FEED_LOAD_COUNT = subscriptionPoolSize;
        Flowable.fromIterable(result)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(getSubscriptionObserver());
        Log.d("handleResult", "end");
    }

    /**
     * Responsible for reacting to user pulling request and starting a request for new feed stream.
     * <p>
     * On initialization, it automatically requests the amount of feed needed to display
     * a minimum amount required (FEED_LOAD_SIZE).
     * <p>
     * Upon receiving a user pull, it creates a Single Observer to fetch the ChannelInfo
     * containing the feed streams.
     **/
    private Subscriber<SubscriptionEntity> getSubscriptionObserver() {
        Log.d("Subscriber", "get");
        return new Subscriber<SubscriptionEntity>() {
            @Override
            public void onSubscribe(Subscription s) {
                Log.d("Subscriber onSubscribe", "start");
                if (feedSubscriber != null) feedSubscriber.cancel();
                feedSubscriber = s;

                int requestSize = FEED_LOAD_COUNT - infoListAdapter.getItemsList().size();
                if (wasLoading.getAndSet(false)) requestSize = FEED_LOAD_COUNT;

                boolean hasToLoad = requestSize > 0;
                if (hasToLoad) {
                    requestLoadedAtomic.set(infoListAdapter.getItemsList().size());
                    requestFeed(requestSize);
                }
                isLoading.set(hasToLoad);
                Log.d("Subscriber onSubscribe", "end");
            }

            @Override
            public void onNext(SubscriptionEntity subscriptionEntity) {
                Log.d("Subscriber onNext", "start");
                if (!itemsLoaded.contains(subscriptionEntity.getServiceId() + subscriptionEntity.getUrl())) {
                    subscriptionService.getChannelInfo(subscriptionEntity)
                            .observeOn(AndroidSchedulers.mainThread())
                            .onErrorComplete(
                                    (@io.reactivex.annotations.NonNull Throwable throwable) ->
                                            FeedFragment.super.onError(throwable))
                            .subscribe(
                                    getChannelInfoObserver(subscriptionEntity.getServiceId(),
                                            subscriptionEntity.getUrl()));
                } else {
                    requestFeed(1);
                }
                Log.d("Subscriber onNext", "end");
            }

            @Override
            public void onError(Throwable exception) {
                Log.d("Subscriber onError", "start");
                FeedFragment.this.onError(exception);
                Log.d("Subscriber onError", "end");
            }

            @Override
            public void onComplete() {
                Log.d("Subscriber onComplete", "start");
                if (DEBUG) Log.d(TAG, "getSubscriptionObserver > onComplete() called");
                Log.d("Subscriber onComplete", "end");
            }
        };
    }

    /**
     * On each request, a subscription item from the updated table is transformed
     * into a ChannelInfo, containing the latest streams from the channel.
     * <p>
     * Currently, the feed uses the first into from the list of streams.
     * <p>
     * If chosen feed already displayed, then we request another feed from another
     * subscription, until the subscription table runs out of new items.
     * <p>
     * This Observer is self-contained and will dispose itself when complete. However, this
     * does not obey the fragment lifecycle and may continue running in the background
     * until it is complete. This is done due to RxJava2 no longer propagate errors once
     * an observer is unsubscribed while the thread process is still running.
     * <p>
     * To solve the above issue, we can either set a global RxJava Error Handler, or
     * manage exceptions case by case. This should be done if the current implementation is
     * too costly when dealing with larger subscription sets.
     *
     * @param url + serviceId to put in {@link #allItemsLoaded} to signal that this specific entity has been loaded.
     */
    private MaybeObserver<ChannelInfo> getChannelInfoObserver(final int serviceId, final String url) {
        Log.d("MaybeObserver", "get");
        return new MaybeObserver<ChannelInfo>() {
            private Disposable observer;

            @SuppressLint("LongLogTag")
            @Override
            public void onSubscribe(Disposable d) {
                Log.d("MaybeObserver onSubscribe", "start");
                observer = d;
                compositeDisposable.add(d);
                isLoading.set(true);
                Log.d("MaybeObserver onSubscribe", "end");
            }

            // Called only when response is non-empty
            @SuppressLint("LongLogTag")
            @Override
            public void onSuccess(final ChannelInfo channelInfo) {
                Log.d("MaybeObserver onSuccess", "start");
                chachedChannel.add(channelInfo);

                loadChannel(channelInfo);

                onDone();
                Log.d("MaybeObserver onSuccess", "end");
            }

            @Override
            public void onError(Throwable exception) {
                Log.d("MaybeObserver onError", "start");
                showSnackBarError(exception,
                        UserAction.SUBSCRIPTION,
                        NewPipe.getNameOfService(serviceId),
                        url, 0);
                requestFeed(1);
                onDone();
                Log.d("MaybeObserver onError", "end");
            }

            // Called only when response is empty
            @SuppressLint("LongLogTag")
            @Override
            public void onComplete() {
                Log.d("MaybeObserver onComplete", "start");
                onDone();
                Log.d("MaybeObserver onComplete", "end");
            }

            private void onDone() {
                Log.d("MaybeObserver onDone", "start");
                if (observer.isDisposed()) {
                    Log.d("MaybeObserver onDone", "end 0");
                    return;
                }

                itemsLoaded.add(serviceId + url);
                compositeDisposable.remove(observer);

                // finished with the day
                //int loaded = requestLoadedAtomic.incrementAndGet();
                //if (loaded >= Math.min(FEED_LOAD_COUNT, subscriptionPoolSize)) {
                if (usedChannels.get(thisLoad).size() == subscriptionPoolSize) {
                    finishedFirstLoad();
                    requestLoadedAtomic.set(0);
                    isLoading.set(false);
                }

                // finished with everything
                //if (itemsLoaded.size() == subscriptionPoolSize) {
                if (thisLoad == 10 && usedChannels.get(10).size() == subscriptionPoolSize) {
                    if (DEBUG) Log.d(TAG, "getChannelInfoObserver > All Items Loaded");
                    allItemsLoaded.set(true);
                    showListFooter(false);
                    isLoading.set(false);
                    hideLoading();
                    if (infoListAdapter.getItemsList().size() == 0) {
                        showEmptyState();
                    }
                }
                Log.d("MaybeObserver onDone", "end 1");
            }
        };
    }

    @Override
    protected void loadMoreItems() {
        Log.d("loadMoreItems", "start");
        if (thisLoad <= 10) {
            isLoading.set(true);
            delayHandler.removeCallbacksAndMessages(null);
            // Add a little of a delay when requesting more items because the cache is so fast,
            // that the view seems stuck to the user when he scroll to the bottom
            delayHandler.postDelayed(this::loadNewItems, 300);
        }
        Log.d("loadMoreItems", "end");
    }

    protected ArrayList<String> sort(ArrayList<String> list) {
        ArrayList<String> endlist = new ArrayList<>();
        for (int x = 1; x < 61; x++) {
            String name = " " + x;
            while (list.contains(name)) {
                endlist.add(name);
                name = " " + x;
            }
        }
        return endlist;
    }

    private ArrayList<String> makeAList(ArrayList<String> list) {
        return list;
    }

    protected void finishedFirstLoad() {
        ArrayList<String> secsArr = new ArrayList<>();
        Map<String, StreamInfoItem> secsMap = new HashMap<>();
        ArrayList<String> minsArr = new ArrayList<>();
        Map<String, StreamInfoItem> minsMap = new HashMap<>();
        ArrayList<String> hrsArr = new ArrayList<>();
        Map<String, StreamInfoItem> hrsMap = new HashMap<>();

        // sort for time type

        for (StreamInfoItem item: todayItems) {
            String uploadDate = item.getUploadDate();
            String newDate = uploadDate.split(" ")[1] + " ";
            if (uploadDate.contains(space_secs) || uploadDate.equals(before_1_sec)) {
                while (secsArr.contains(newDate)) {
                    newDate += " ";
                }
                secsArr.add(newDate);
                secsMap.put(newDate, item);
            } else if (uploadDate.contains(space_mins) || uploadDate.equals(before_1_min)) {
                while (minsArr.contains(newDate)) {
                    newDate += " ";
                }
                minsArr.add(newDate);
                minsMap.put(newDate, item);
            } else if (uploadDate.contains(space_hrs) || uploadDate.contains(before_1_hr)) {
                while (hrsArr.contains(newDate)) {
                    newDate += " ";
                }
                hrsArr.add(newDate);
                hrsMap.put(newDate, item);
            }
        }
        // sort for numbers

        Collections.sort(secsArr);
        Collections.sort(minsArr);
        Collections.sort(hrsArr);

        //secsArr = sort(secsArr);
        //minsArr = sort(minsArr);
        //hrsArr = sort(hrsArr);

        // apply
        for (String aNum: secsArr) {
            infoListAdapter.addInfoItem(secsMap.get(aNum));
        }for (String aNum: minsArr) {
            infoListAdapter.addInfoItem(minsMap.get(aNum));
        }for (String aNum: hrsArr) {
            infoListAdapter.addInfoItem(hrsMap.get(aNum));
        }
        /*
        ArrayList<StreamInfoItem> lastList = new ArrayList<>();
        for (String aNum: secsArr) {
            lastList.add(secsMap.get(aNum));
        }for (String aNum: minsArr) {
            lastList.add(minsMap.get(aNum));
        }for (String aNum: hrsArr) {
            lastList.add(hrsMap.get(aNum));
        }

        for (StreamInfoItem item: lastList) {
            infoListAdapter.addInfoItem(item);
        }


        Log.d("END_ARRAY", lastList.toString());
        */
        Log.d("END", "END");

    }

    private void loadNewItems() {
        Log.d("loadNewItems", "start");
        thisLoad++;
        for (ChannelInfo channelInfo: chachedChannel) {
            loadChannel(channelInfo);
        }

        // onDone
        if (usedChannels.get(thisLoad).size() == subscriptionPoolSize) {
            requestLoadedAtomic.set(0);
            isLoading.set(false);
        }
        if (thisLoad == 10 && usedChannels.get(10).size() == subscriptionPoolSize) {
            if (DEBUG) Log.d(TAG, "getChannelInfoObserver > All Items Loaded");
            allItemsLoaded.set(true);
            showListFooter(false);
            isLoading.set(false);
            hideLoading();
            if (infoListAdapter.getItemsList().size() == 0) {
                showEmptyState();
            }
        }

        Log.d("loadNewItems", "end");
    }

    private void loadChannel(ChannelInfo channelInfo) {
        if (thisLoad > 10) return;
        if (infoListAdapter == null || channelInfo.getRelatedItems().isEmpty()) {
            if (!usedChannels.get(thisLoad).contains(channelInfo.getUrl())) {
                usedChannels.get(thisLoad).add(channelInfo.getUrl());
            }
            return;
        }

        num = 0;
        StreamInfoItem item = channelInfo.getRelatedItems().get(num);
        Log.d("Feeder", "Reloading Channel: " + channelInfo.getName() + "\nThe first item: " + item.getName() + "\nItem upload date: " + item.getUploadDate() + "\nThe load: " + loadParse[thisLoad]);
        String uploadDate = item.getUploadDate();

        if (true) {
            if (thisLoad == 0) { // today
                while ((uploadDate.equals(before_1_sec) || uploadDate.endsWith(space_secs) ||
                        uploadDate.equals(before_1_min) || uploadDate.endsWith(space_mins) ||
                        uploadDate.equals(before_1_hr) || uploadDate.endsWith(space_hrs)) && channelInfo.getRelatedItems().size() > num) {

                    //add to list
                    if (!doesItemExist(infoListAdapter.getItemsList(), item)) {
                        Log.d("Feeder", "Adding the item: " + item.getName());
                        todayItems.add(item);
                        //TODO HERE DELETE
                        //infoListAdapter.addInfoItem(item);
                    }
                    num++;
                    if (channelInfo.getRelatedItems().size() > num) {
                        item = channelInfo.getRelatedItems().get(num);
                        Log.d("Feeder", "Getting next item: " + item.getName());
                        uploadDate = item.getUploadDate();
                    }
                }
            } else {
                String keyWord = loadParse[0];
                while ((uploadDate.equals(before_1_sec) || uploadDate.endsWith(space_secs) ||
                        uploadDate.equals(before_1_min) || uploadDate.endsWith(space_mins) ||
                        uploadDate.equals(before_1_hr) || uploadDate.endsWith(space_hrs))) {
                    //wait
                    num++;
                    Log.d("Feeder", "Waiting in loop: today\nNum: " + num);
                    if (channelInfo.getRelatedItems().size() > num) {
                        item = channelInfo.getRelatedItems().get(num);
                        Log.d("Feeder", "Getting next item: " + item.getName());
                        uploadDate = item.getUploadDate();
                    }
                }
                Log.d("Feeder", "Trying: yesterday, Num: " + num);
                if (thisLoad == 1) { // yesterday
                    keyWord = loadParse[thisLoad];
                } else {
                    while (uploadDate.equals(loadParse[1]) && channelInfo.getRelatedItems().size() > num) {
                        //wait
                        Log.d("Feeder", "Waiting in loop: yesterday\nNum: " + num);
                        num++;
                        if (channelInfo.getRelatedItems().size() > num) {
                            item = channelInfo.getRelatedItems().get(num);
                            Log.d("Feeder", "Getting next item: " + item.getName());
                            uploadDate = item.getUploadDate();
                        } else break;
                    }
                    Log.d("Feeder", "Trying: 2 days ago, Num: " + num);
                    if (thisLoad == 2) { // 2 days ago
                        keyWord = loadParse[thisLoad];
                    } else {
                        while (uploadDate.equals(loadParse[2]) && channelInfo.getRelatedItems().size() > num) {
                            //wait
                            num++;
                            Log.d("Feeder", "Waiting in loop: 2 days ago\nNum: " + num);
                            if (channelInfo.getRelatedItems().size() > num) {
                                item = channelInfo.getRelatedItems().get(num);
                                Log.d("Feeder", "Getting next item: " + item.getName());
                                uploadDate = item.getUploadDate();
                            } else break;
                        }
                        Log.d("Feeder", "Trying: 3 days ago, Num: " + num);
                        if (thisLoad == 3) { // 3 days ago
                            keyWord = loadParse[thisLoad];
                        } else {
                            while (uploadDate.equals(loadParse[3]) && channelInfo.getRelatedItems().size() > num) {
                                //wait
                                num++;
                                Log.d("Feeder", "Waiting in loop: 3 days ago\nNum: " + num);
                                if (channelInfo.getRelatedItems().size() > num) {
                                    item = channelInfo.getRelatedItems().get(num);
                                    Log.d("Feeder", "Getting next item: " + item.getName());
                                    uploadDate = item.getUploadDate();
                                } else break;
                            }
                            Log.d("Feeder", "Trying: 4 days ago, Num: " + num);
                            if (thisLoad == 4) { // 4 days ago
                                keyWord = loadParse[thisLoad];
                            } else {
                                while (uploadDate.equals(loadParse[4]) && channelInfo.getRelatedItems().size() > num) {
                                    //wait
                                    num++;
                                    Log.d("Feeder", "Waiting in loop: 4 days ago\nNum: " + num);
                                    if (channelInfo.getRelatedItems().size() > num) {
                                        item = channelInfo.getRelatedItems().get(num);
                                        Log.d("Feeder", "Getting next item: " + item.getName());
                                        uploadDate = item.getUploadDate();
                                    } else break;
                                }
                                Log.d("Feeder", "Trying: 5 days ago, Num: " + num);
                                if (thisLoad == 5) { // 5 days ago
                                    keyWord = loadParse[thisLoad];
                                } else {
                                    while (uploadDate.equals(loadParse[5]) && channelInfo.getRelatedItems().size() > num) {
                                        //wait
                                        num++;
                                        Log.d("Feeder", "Waiting in loop: 5 days ago\nNum: " + num);
                                        if (channelInfo.getRelatedItems().size() > num) {
                                            item = channelInfo.getRelatedItems().get(num);
                                            Log.d("Feeder", "Getting next item: " + item.getName());
                                            uploadDate = item.getUploadDate();
                                        } else break;
                                    }
                                    Log.d("Feeder", "Trying: 6 days ago, Num: " + num);
                                    if (thisLoad == 6) { // 6 days ago
                                        keyWord = loadParse[thisLoad];
                                    } else {
                                        while (uploadDate.equals(loadParse[6]) && channelInfo.getRelatedItems().size() > num) {
                                            //wait
                                            num++;
                                            Log.d("Feeder", "Waiting in loop: 6 days ago\nNum: " + num);
                                            if (channelInfo.getRelatedItems().size() > num) {
                                                item = channelInfo.getRelatedItems().get(num);
                                                Log.d("Feeder", "Getting next item: " + item.getName());
                                                uploadDate = item.getUploadDate();
                                            } else break;
                                        }
                                        Log.d("Feeder", "Trying: 1 week ago, Num: " + num);
                                        if (thisLoad == 7) { // 1 week ago
                                            keyWord = loadParse[thisLoad];
                                        } else {
                                            while (uploadDate.equals(loadParse[7]) && channelInfo.getRelatedItems().size() > num) {
                                                //wait
                                                num++;
                                                Log.d("Feeder", "Waiting in loop: 1 week ago\nNum: " + num);
                                                if (channelInfo.getRelatedItems().size() > num) {
                                                    item = channelInfo.getRelatedItems().get(num);
                                                    Log.d("Feeder", "Getting next item: " + item.getName());
                                                    uploadDate = item.getUploadDate();
                                                } else break;
                                            }
                                            Log.d("Feeder", "Trying: 2 weeks ago, Num: " + num);
                                            if (thisLoad == 8) { // 2 weeks ago
                                                keyWord = loadParse[thisLoad];
                                            } else {
                                                while (uploadDate.equals(loadParse[8]) && channelInfo.getRelatedItems().size() > num) {
                                                    //wait
                                                    num++;
                                                    Log.d("Feeder", "Waiting in loop: 2 weeks ago\nNum: " + num);
                                                    if (channelInfo.getRelatedItems().size() > num) {
                                                        item = channelInfo.getRelatedItems().get(num);
                                                        Log.d("Feeder", "Getting next item: " + item.getName());
                                                        uploadDate = item.getUploadDate();
                                                    } else break;
                                                }
                                                Log.d("Feeder", "Trying: 3 weeks ago, Num: " + num);
                                                if (thisLoad == 9) { // 3 weeks ago
                                                    keyWord = loadParse[thisLoad];
                                                } else {
                                                    while (uploadDate.equals(loadParse[9]) && channelInfo.getRelatedItems().size() > num) {
                                                        //wait
                                                        num++;
                                                        Log.d("Feeder", "Waiting in loop: 3 weeks ago\nNum: " + num);
                                                        if (channelInfo.getRelatedItems().size() > num) {
                                                            item = channelInfo.getRelatedItems().get(num);
                                                            Log.d("Feeder", "Getting next item: " + item.getName());
                                                            uploadDate = item.getUploadDate();
                                                        } else break;
                                                    }
                                                    Log.d("Feeder", "Trying: 1 month ago, Num: " + num);
                                                    if (thisLoad == 10) { // 1 month ago
                                                        keyWord = loadParse[thisLoad];
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                //Log.d("Logging", "Hello I'm HERE: keyWord: " + keyWord + " and the uploadDate: " + uploadDate + " and the Num: " + num);
                // num = 0;
                while (uploadDate.equals(keyWord) && channelInfo.getRelatedItems().size() > num) {
                    //add to list
                    if (!doesItemExist(infoListAdapter.getItemsList(), item)) {
                        Log.d("Feeder", "Adding the item: " + item.getName());
                        infoListAdapter.addInfoItem(item);
                    }
                    num++;
                    Log.d("Feeder", "Num: " + num);
                    if (channelInfo.getRelatedItems().size() > num) {
                        item = channelInfo.getRelatedItems().get(num);
                        Log.d("Feeder", "Getting next item: " + item.getName());
                        uploadDate = item.getUploadDate();
                    }
                }
            }
        } else {
            while (num < 5) {
                //add X to list
                if (!doesItemExist(infoListAdapter.getItemsList(), item)) {
                    infoListAdapter.addInfoItem(item);
                }
                num++;
                item = channelInfo.getRelatedItems().get(num);
            }
        }
        num = 0;
        if (!usedChannels.get(thisLoad).contains(channelInfo.getUrl())) {
            usedChannels.get(thisLoad).add(channelInfo.getUrl());
        }
    }

    @Override
    protected boolean hasMoreItems() {
        Log.d("hasMoreItems", "start");
        Log.d("hasMoreItems", "" + (thisLoad <= 10));
        Log.d("hasMoreItems", "end");
        //return !allItemsLoaded.get();
        return thisLoad <= 9;
    }

    private final Handler delayHandler = new Handler();

    private void requestFeed(final int count) {
        Log.d("requestFeed", "start");
        if (DEBUG) Log.d(TAG, "requestFeed() called with: count = [" + count + "], feedSubscriber = [" + feedSubscriber + "]");
        if (feedSubscriber == null) return;

        isLoading.set(true);
        delayHandler.removeCallbacksAndMessages(null);
        feedSubscriber.request(count);
        Log.d("requestFeed", "end");
    }

    @Override
    protected void onScrollToBottom() {
        if (hasMoreItems() && !isLoading.get()) {
            loadMoreItems();
        }
        Log.d("onScrollToBottom", "Loading: " + isLoading.toString());
        Log.d("onScrollToBottom", "end");
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private void resetFragment() {
        Log.d("resetFragment", "start");
        if (DEBUG) Log.d(TAG, "resetFragment() called");
        if (subscriptionObserver != null) subscriptionObserver.dispose();
        if (compositeDisposable != null) compositeDisposable.clear();
        if (infoListAdapter != null) infoListAdapter.clearStreamItemList();

        delayHandler.removeCallbacksAndMessages(null);
        requestLoadedAtomic.set(0);
        allItemsLoaded.set(false);
        showListFooter(false);
        itemsLoaded.clear();
        Log.d("resetFragment", "end");
    }

    private void disposeEverything() {
        Log.d("disposeEverything", "start");
        if (subscriptionObserver != null) subscriptionObserver.dispose();
        if (compositeDisposable != null) compositeDisposable.clear();
        if (feedSubscriber != null) feedSubscriber.cancel();
        delayHandler.removeCallbacksAndMessages(null);
        Log.d("resetFragment", "end");
    }

    private boolean doesItemExist(final List<InfoItem> items, final InfoItem item) {
        //Log.d("doesItemExist", "start");
        for (final InfoItem existingItem : items) {
            if (existingItem.getInfoType() == item.getInfoType() &&
                    existingItem.getServiceId() == item.getServiceId() &&
                    existingItem.getName().equals(item.getName()) &&
                    existingItem.getUrl().equals(item.getUrl())) {
                Log.d("doesItemExist", "end 0");
                return true;
            }
        }
        //Log.d("doesItemExist", "end 1");
        return false;
    }

    private int howManyItemsToLoad() {
        Log.d("howManyItemsToLoad", "start");
        int heightPixels = getResources().getDisplayMetrics().heightPixels;
        int itemHeightPixels = activity.getResources().getDimensionPixelSize(R.dimen.video_item_search_height);

        int items = itemHeightPixels > 0
                ? heightPixels / itemHeightPixels + OFF_SCREEN_ITEMS_COUNT
                : MIN_ITEMS_INITIAL_LOAD;
        Log.d("howManyItemsToLoad", "" + items); // ouch
        Log.d("howManyItemsToLoad", "end");
        return Math.max(MIN_ITEMS_INITIAL_LOAD, items);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment Error Handling
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void showError(String message, boolean showRetryButton) {
        Log.d("showError", "start");
        resetFragment();
        super.showError(message, showRetryButton);
        Log.d("showError", "end");
    }

    @Override
    protected boolean onError(Throwable exception) {
        Log.d("onError", "start");
        if (super.onError(exception)) {
            Log.d("onError", "end 0");
            return true;
        }

        int errorId = exception instanceof ExtractionException
                ? R.string.parsing_error
                : R.string.general_error;
        onUnrecoverableError(exception,
                UserAction.SOMETHING_ELSE,
                "none",
                "Requesting feed",
                errorId);
        Log.d("onError", "end 1");
        return true;
    }
}
