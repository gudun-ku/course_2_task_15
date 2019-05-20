package com.elegion.myfirstapplication.album;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.elegion.myfirstapplication.ApiUtils;
import com.elegion.myfirstapplication.App;
import com.elegion.myfirstapplication.R;
import com.elegion.myfirstapplication.comments.CommentsAlbumFragment;
import com.elegion.myfirstapplication.db.AlbumSong;
import com.elegion.myfirstapplication.db.MusicDao;
import com.elegion.myfirstapplication.model.Album;
import com.elegion.myfirstapplication.model.Song;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;


public class DetailAlbumFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener {
    private static final String ALBUM_KEY = "ALBUM_KEY";

    private Disposable mSubscription, mAlbumSubscription, mSongSubscription;
    private RecyclerView mRecyclerView;
    private SwipeRefreshLayout mRefresher;
    private View mErrorView;
    private Album mAlbum;

    @NonNull
    private final SongsAdapter mSongsAdapter = new SongsAdapter();

    public static DetailAlbumFragment newInstance(Album album) {
        Bundle args = new Bundle();
        args.putSerializable(ALBUM_KEY, album);

        DetailAlbumFragment fragment = new DetailAlbumFragment();
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fr_refresher, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mRecyclerView = view.findViewById(R.id.recycler);
        mRefresher = view.findViewById(R.id.refresher);
        mRefresher.setOnRefreshListener(this);
        mErrorView = view.findViewById(R.id.errorView);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mAlbum = (Album) getArguments().getSerializable(ALBUM_KEY);

        getActivity().setTitle(mAlbum.getName());

        mRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
        mRecyclerView.setAdapter(mSongsAdapter);

        onRefresh();
    }

    @Override
    public void onRefresh() {
        mRefresher.post(new Runnable() {
                            @Override
                            public void run() {
                                mRefresher.setRefreshing(true);
                                getAlbum();
                            }
                        }
            );
    }

    private void getAlbum() {

        final MusicDao dao = getMusicDao();
        mSubscription = ApiUtils.getApiService()
            .getAlbum(mAlbum.getId())
            .doOnSuccess(new Consumer<Album>() {
                @Override
                public void accept(Album album) throws Exception {
                    dao.insertAlbum(album);
                    dao.deleteAlbumSongsByAlbumId(album.getId());
                    for (Song song: album.getSongs()) {
                        dao.insertSong(song);
                        dao.setLinkAlbumSongs(new AlbumSong(0,album.getId(), song.getId()));
                    }
                }
            })
            .doOnSubscribe(new Consumer<Disposable>() {
                @Override
                public void accept(Disposable disposable) throws Exception {
                    mRefresher.setRefreshing(true);
                }
            })
            .onErrorReturn(new Function<Throwable, Album>() {
                @Override
                public Album apply(Throwable throwable) throws Exception {
                    if (ApiUtils.NETWORK_EXCEPTION.contains(throwable.getClass())) {
                        return mAlbum;
                    } else {
                        return null;
                    }
                }
            })
            .flatMap(new Function<Album,Single<Album>>() {
                @Override
                public Single<Album> apply(Album album) throws Exception {
                    if (mAlbum.getSongs() == null) {
                        return Single.just(getAlbumWithSongsFromDB(mAlbum, dao));
                    } else {
                        return Single.just(mAlbum);
                    }
                }
            })
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeOn(Schedulers.io())
            .doFinally(new Action() {
                @Override
                public void run() throws Exception {
                    mRefresher.setRefreshing(false);
                }
            })
            .subscribe(
                new Consumer<Album>() {
                    @Override
                    public void accept(Album album) throws Exception {
                        if (album.getSongs() == null || album.getSongs().size() == 0) {
                            mErrorView.setVisibility(View.VISIBLE);
                            mRecyclerView.setVisibility(View.GONE);
                        } else {
                            mErrorView.setVisibility(View.GONE);
                            mRecyclerView.setVisibility(View.VISIBLE);
                            List<Song> songs = album.getSongs();
                            // sort by id
                            Collections.sort(songs, new SongIdComparator());
                            mSongsAdapter.addData(songs, true);
                        }
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        mErrorView.setVisibility(View.VISIBLE);
                        mRecyclerView.setVisibility(View.GONE);
                    }
                }
            );

    }


    private Album getAlbumWithSongsFromDB(Album mAlbum, MusicDao dao) {
        Album album = dao.getAlbumWithId(mAlbum.getId());
        List <Song> songs = dao.getSongsFromAlbum(mAlbum.getId());
        album.setSongs(songs);
        return album;
    }


    @Override
    public void onDestroy() {
        mSubscription.dispose();
        super.onDestroy();
    }

    private MusicDao getMusicDao() {
        return ((App) getActivity().getApplication()).getDatabase().getMusicDao();
    }

    // Comparator for list sorting
    private class SongIdComparator implements Comparator<Song>
    {
        public int compare(Song left, Song right) {
            return left.getId() - right.getId();
        }
    }

    // Menu item for comments

    @Override
    public void onCreateOptionsMenu(
            Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_album_comments, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // handle item selection
        switch (item.getItemId()) {
            case R.id.actionComments:
                gotoCommentsFragment(mAlbum);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void gotoCommentsFragment(Album album) {
        getFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, CommentsAlbumFragment.newInstance(album))
                .addToBackStack(CommentsAlbumFragment.class.getName())
                .commit();
    }
}
