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
import java.util.HashSet;
import java.util.List;
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

    private List<SubscriptionEntity> chachedResult;
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
    * 10: 28-X  vor 4 Wochen
    * Monat not supportet
    * */

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
        Log.e("onCreate", "start");
        super.onCreate(savedInstanceState);
        subscriptionService = SubscriptionService.getInstance(activity);
        usedChannels = new ArrayList<>();
        for (int x=0; x<11; x++) {
            usedChannels.add(new ArrayList<String>());
        }
        thisLoad = 0;
        chachedChannel = new ArrayList<>();

        FEED_LOAD_COUNT = howManyItemsToLoad();
        Log.e("onCreate", "end");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {

        Log.e("onCreateView", "start");
        if(!useAsFrontPage) {
            setTitle(activity.getString(R.string.fragment_whats_new));
        }
        Log.e("onCreateView", "end");
        return inflater.inflate(R.layout.fragment_feed, container, false);
    }

    @Override
    public void onPause() {
        Log.e("onPause", "start");
        super.onPause();
        disposeEverything();
        Log.e("onPause", "end");
    }

    @Override
    public void onResume() {
        Log.e("onResume", "start");
        super.onResume();
        if (wasLoading.get()) doInitialLoadLogic();
        Log.e("onResume", "end");
    }

    @Override
    public void onDestroy() {
        Log.e("onDestroy", "start");
        super.onDestroy();

        disposeEverything();
        subscriptionService = null;
        compositeDisposable = null;
        subscriptionObserver = null;
        feedSubscriber = null;
        Log.e("onDestroy", "end");
    }

    @Override
    public void onDestroyView() {
        // Do not monitor for updates when user is not viewing the feed fragment.
        // This is a waste of bandwidth.
        Log.e("onDestroyView", "start");
        disposeEverything();
        super.onDestroyView();
        Log.e("onDestroyView", "end");
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        Log.e("setUserVisibleHint", "start");
        super.setUserVisibleHint(isVisibleToUser);
        Log.e("ERROOR", "setUserVisibleHint");
        if (activity != null && isVisibleToUser) {
            setTitle(activity.getString(R.string.fragment_whats_new));
        }
        Log.e("setUserVisibleHint", "end");
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        Log.e("setUserVisibleHint", "start");
        super.onCreateOptionsMenu(menu, inflater);

        ActionBar supportActionBar = activity.getSupportActionBar();

        if(useAsFrontPage) {
            supportActionBar.setDisplayShowTitleEnabled(true);
            //supportActionBar.setDisplayShowTitleEnabled(false);
        }
        Log.e("onCreateOptionsMenu", "end");
    }

    @Override
    public void reloadContent() {
        Log.e("reloadContent", "start");
        resetFragment();
        super.reloadContent();
        Log.e("reloadContent", "end");
    }

    /*//////////////////////////////////////////////////////////////////////////
    // StateSaving
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void writeTo(Queue<Object> objectsToSave) {
        Log.e("writeTo", "start");
        super.writeTo(objectsToSave);
        objectsToSave.add(allItemsLoaded);
        objectsToSave.add(itemsLoaded);
        Log.e("writeTo", "end");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void readFrom(@NonNull Queue<Object> savedObjects) throws Exception {
        Log.e("readFrom", "start");
        super.readFrom(savedObjects);
        allItemsLoaded = (AtomicBoolean) savedObjects.poll();
        itemsLoaded = (HashSet<String>) savedObjects.poll();
        Log.e("readFrom", "end");
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Feed Loader
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void startLoading(boolean forceLoad) {
        Log.e("startLoading", "start");
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
            Log.e("startLoading", "end 0");
            return;
        }

        isLoading.set(true);
        showLoading();
        showListFooter(true);
        subscriptionObserver = subscriptionService.getSubscription()
                .onErrorReturnItem(Collections.emptyList())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handleResult, this::onError);

        Log.e("startLoading", "End 1");
    }

    @Override
    public void handleResult(@android.support.annotation.NonNull List<SubscriptionEntity> result) {
        Log.e("handleResult", "start");
        super.handleResult(result);
        chachedResult = result;
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
        Log.e("handleResult", "end");
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
        Log.e("Subscriber", "get");
        return new Subscriber<SubscriptionEntity>() {
            @Override
            public void onSubscribe(Subscription s) {
                Log.e("Subscriber onSubscribe", "start");
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
                Log.e("Subscriber onSubscribe", "end");
            }

            @Override
            public void onNext(SubscriptionEntity subscriptionEntity) {
                Log.e("Subscriber onNext", "start");
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
                Log.e("Subscriber onNext", "end");
            }

            @Override
            public void onError(Throwable exception) {
                Log.e("Subscriber onError", "start");
                FeedFragment.this.onError(exception);
                Log.e("Subscriber onError", "end");
            }

            @Override
            public void onComplete() {
                Log.e("Subscriber onComplete", "start");
                if (DEBUG) Log.d(TAG, "getSubscriptionObserver > onComplete() called");
                Log.e("Subscriber onComplete", "end");
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
        Log.e("MaybeObserver", "get");
        return new MaybeObserver<ChannelInfo>() {
            private Disposable observer;

            @SuppressLint("LongLogTag")
            @Override
            public void onSubscribe(Disposable d) {
                Log.e("MaybeObserver onSubscribe", "start");
                observer = d;
                compositeDisposable.add(d);
                isLoading.set(true);
                Log.e("MaybeObserver onSubscribe", "end");
            }

            // Called only when response is non-empty
            @SuppressLint("LongLogTag")
            @Override
            public void onSuccess(final ChannelInfo channelInfo) {
                Log.e("MaybeObserver onSuccess", "start");
                chachedChannel.add(channelInfo);

                loadChannel(channelInfo);

                onDone();
                Log.e("MaybeObserver onSuccess", "end");
            }

            @Override
            public void onError(Throwable exception) {
                Log.e("MaybeObserver onError", "start");
                showSnackBarError(exception,
                        UserAction.SUBSCRIPTION,
                        NewPipe.getNameOfService(serviceId),
                        url, 0);
                requestFeed(1);
                onDone();
                Log.e("MaybeObserver onError", "end");
            }

            // Called only when response is empty
            @SuppressLint("LongLogTag")
            @Override
            public void onComplete() {
                Log.e("MaybeObserver onComplete", "start");
                onDone();
                Log.e("MaybeObserver onComplete", "end");
            }

            private void onDone() {
                Log.e("MaybeObserver onDone", "start");
                if (observer.isDisposed()) {
                    Log.e("MaybeObserver onDone", "end 0");
                    return;
                }

                itemsLoaded.add(serviceId + url);
                compositeDisposable.remove(observer);

                // finished with the day
                //int loaded = requestLoadedAtomic.incrementAndGet();
                //if (loaded >= Math.min(FEED_LOAD_COUNT, subscriptionPoolSize)) {
                if (usedChannels.get(thisLoad).size() == subscriptionPoolSize) {
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
                Log.e("MaybeObserver onDone", "end 1");
            }
        };
    }

    @Override
    protected void loadMoreItems() {
        Log.e("loadMoreItems", "start");
        if (thisLoad <= 10) {
            isLoading.set(true);
            delayHandler.removeCallbacksAndMessages(null);
            // Add a little of a delay when requesting more items because the cache is so fast,
            // that the view seems stuck to the user when he scroll to the bottom
            delayHandler.postDelayed(this::loadNewItems, 300);
        }


        Log.e("loadMoreItems", "end");
    }

    private void loadNewItems() {
        Log.e("loadNewItems", "start");
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

        Log.e("loadNewItems", "end");
    }

    private void loadChannel(ChannelInfo channelInfo) {
        if (thisLoad > 10) return;
        if (infoListAdapter == null || channelInfo.getRelatedItems().isEmpty()) {
            if (!usedChannels.get(thisLoad).contains(channelInfo.getUrl())) {
                usedChannels.get(thisLoad).add(channelInfo.getUrl());
            }
            return;
        }

        StreamInfoItem item = channelInfo.getRelatedItems().get(0);
        String uploadDate = item.getUploadDate();

        int num = 0;
        if (true) {
            if (thisLoad == 0) { // today
                while (uploadDate.endsWith("Sekunde") || uploadDate.endsWith("Sekunden") ||
                        uploadDate.endsWith("Minute") || uploadDate.endsWith("Minuten") ||
                        uploadDate.endsWith("Stunde") || uploadDate.endsWith("Stunden")) {

                    //add to list
                    if (!doesItemExist(infoListAdapter.getItemsList(), item)) {
                        infoListAdapter.addInfoItem(item);
                    }
                    num++;
                    item = channelInfo.getRelatedItems().get(num);
                    uploadDate = item.getUploadDate();
                }
            } else {
                String keyWord = "vor X Tagen";
                while (uploadDate.endsWith("Sekunde") || uploadDate.endsWith("Sekunden") ||
                        uploadDate.endsWith("Minute") || uploadDate.endsWith("Minuten") ||
                        uploadDate.endsWith("Stunde") || uploadDate.endsWith("Stunden")) {
                    //wait
                    num++;
                    item = channelInfo.getRelatedItems().get(num);
                    uploadDate = item.getUploadDate();
                }
                if (thisLoad == 1) { // yesterday
                    keyWord = "vor 1 Tag";
                } else {
                    while (uploadDate.equals("vor 1 Tag")) {
                        //wait
                        num++;
                        item = channelInfo.getRelatedItems().get(num);
                        uploadDate = item.getUploadDate();
                    }
                    if (thisLoad == 2) { // 2 days ago
                        keyWord = "vor 2 Tagen";
                    } else {
                        while (uploadDate.equals("vor 2 Tagen")) {
                            //wait
                            num++;
                            item = channelInfo.getRelatedItems().get(num);
                            uploadDate = item.getUploadDate();
                        }
                        if (thisLoad == 3) { // 3 days ago
                            keyWord = "vor 3 Tagen";
                        } else {
                            while (uploadDate.equals("vor 3 Tagen")) {
                                //wait
                                num++;
                                item = channelInfo.getRelatedItems().get(num);
                                uploadDate = item.getUploadDate();
                            }
                            if (thisLoad == 4) { // 4 days ago
                                keyWord = "vor 4 Tagen";
                            } else {
                                while (uploadDate.equals("vor 4 Tagen")) {
                                    //wait
                                    num++;
                                    item = channelInfo.getRelatedItems().get(num);
                                    uploadDate = item.getUploadDate();
                                }
                                if (thisLoad == 5) { // 5 days ago
                                    keyWord = "vor 5 Tagen";
                                } else {
                                    while (uploadDate.equals("vor 5 Tagen")) {
                                        //wait
                                        num++;
                                        item = channelInfo.getRelatedItems().get(num);
                                        uploadDate = item.getUploadDate();
                                    }
                                    if (thisLoad == 6) { // 6 days ago
                                        keyWord = "vor 6 Tagen";
                                    } else {
                                        while (uploadDate.equals("vor 6 Tagen")) {
                                            //wait
                                            num++;
                                            item = channelInfo.getRelatedItems().get(num);
                                            uploadDate = item.getUploadDate();
                                        }
                                        if (thisLoad == 7) { // 1 week ago
                                            keyWord = "vor 1 Woche";
                                        } else {
                                            while (uploadDate.equals("vor 1 Woche")) {
                                                //wait
                                                num++;
                                                item = channelInfo.getRelatedItems().get(num);
                                                uploadDate = item.getUploadDate();
                                            }
                                            if (thisLoad == 8) { // 2 weeks ago
                                                keyWord = "vor 2 Wochen";
                                            } else {
                                                while (uploadDate.equals("vor 2 Wochen")) {
                                                    //wait
                                                    num++;
                                                    item = channelInfo.getRelatedItems().get(num);
                                                    uploadDate = item.getUploadDate();
                                                }
                                                if (thisLoad == 9) { // 4 weeks ago
                                                    keyWord = "vor 3 Wochen";
                                                } else {
                                                    while (uploadDate.equals("vor 3 Wochen")) {
                                                        //wait
                                                        num++;
                                                        item = channelInfo.getRelatedItems().get(num);
                                                        uploadDate = item.getUploadDate();
                                                    }
                                                    if (thisLoad == 10) { // 1 month ago
                                                        keyWord = "vor 1 Monat";
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
                Log.e("Logging", "Hello I'm HERE: keyWord: " + keyWord + " and the uploadDate: " + uploadDate);
                while (uploadDate.equals(keyWord)) {
                    //add to list
                    if (!doesItemExist(infoListAdapter.getItemsList(), item)) {
                        infoListAdapter.addInfoItem(item);
                    }
                    num++;
                    Log.i("Nummer: ", "" + num);
                    item = channelInfo.getRelatedItems().get(num);
                    uploadDate = item.getUploadDate();
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
                //uploadDate = item.getUploadDate();
            }
        }



        if (!usedChannels.get(thisLoad).contains(channelInfo.getUrl())) {
            usedChannels.get(thisLoad).add(channelInfo.getUrl());
        }
    }

    @Override
    protected boolean hasMoreItems() {
        Log.e("hasMoreItems", "start");
        Log.e("hasMoreItems", "" + (thisLoad <= 10));
        Log.e("hasMoreItems", "end");
        //return !allItemsLoaded.get();
        return thisLoad <= 9;
    }

    private final Handler delayHandler = new Handler();

    private void requestFeed(final int count) {
        Log.e("requestFeed", "start");
        if (DEBUG) Log.d(TAG, "requestFeed() called with: count = [" + count + "], feedSubscriber = [" + feedSubscriber + "]");
        if (feedSubscriber == null) return;

        isLoading.set(true);
        delayHandler.removeCallbacksAndMessages(null);
        feedSubscriber.request(count);
        Log.e("requestFeed", "end");
    }

    @Override
    protected void onScrollToBottom() {
        if (hasMoreItems() && !isLoading.get()) {
            loadMoreItems();
        }
        Log.e("onScrollToBottom", isLoading.toString());
        Log.e("onScrollToBottom", "end");
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private void resetFragment() {
        Log.e("resetFragment", "start");
        if (DEBUG) Log.d(TAG, "resetFragment() called");
        if (subscriptionObserver != null) subscriptionObserver.dispose();
        if (compositeDisposable != null) compositeDisposable.clear();
        if (infoListAdapter != null) infoListAdapter.clearStreamItemList();

        delayHandler.removeCallbacksAndMessages(null);
        requestLoadedAtomic.set(0);
        allItemsLoaded.set(false);
        showListFooter(false);
        itemsLoaded.clear();
        Log.e("resetFragment", "end");
    }

    private void disposeEverything() {
        Log.e("disposeEverything", "start");
        if (subscriptionObserver != null) subscriptionObserver.dispose();
        if (compositeDisposable != null) compositeDisposable.clear();
        if (feedSubscriber != null) feedSubscriber.cancel();
        delayHandler.removeCallbacksAndMessages(null);
        Log.e("resetFragment", "end");
    }

    private boolean doesItemExist(final List<InfoItem> items, final InfoItem item) {
        Log.e("doesItemExist", "start");
        for (final InfoItem existingItem : items) {
            if (existingItem.getInfoType() == item.getInfoType() &&
                    existingItem.getServiceId() == item.getServiceId() &&
                    existingItem.getName().equals(item.getName()) &&
                    existingItem.getUrl().equals(item.getUrl())) {
                Log.e("doesItemExist", "end 0");
                return true;
            }
        }
        Log.e("doesItemExist", "end 1");
        return false;
    }

    private int howManyItemsToLoad() {
        Log.e("howManyItemsToLoad", "start");
        int heightPixels = getResources().getDisplayMetrics().heightPixels;
        int itemHeightPixels = activity.getResources().getDimensionPixelSize(R.dimen.video_item_search_height);

        int items = itemHeightPixels > 0
                ? heightPixels / itemHeightPixels + OFF_SCREEN_ITEMS_COUNT
                : MIN_ITEMS_INITIAL_LOAD;
        Log.e("howManyItemsToLoad", "" + items); // ouch
        Log.e("howManyItemsToLoad", "end");
        return Math.max(MIN_ITEMS_INITIAL_LOAD, items);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment Error Handling
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void showError(String message, boolean showRetryButton) {
        Log.e("showError", "start");
        resetFragment();
        super.showError(message, showRetryButton);
        Log.e("showError", "end");
    }

    @Override
    protected boolean onError(Throwable exception) {
        Log.e("onError", "start");
        if (super.onError(exception)) {
            Log.e("onError", "end 0");
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
        Log.e("onError", "end 1");
        return true;
    }
}
