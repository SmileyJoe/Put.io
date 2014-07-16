package com.stevenschoen.putionew.fragments;

import android.app.Activity;
import android.app.Dialog;
import android.app.Fragment;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.util.SparseBooleanArray;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SearchView;

import com.stevenschoen.putionew.FilesAdapter;
import com.stevenschoen.putionew.PutioApplication;
import com.stevenschoen.putionew.PutioFileLayout;
import com.stevenschoen.putionew.PutioUtils;
import com.stevenschoen.putionew.R;
import com.stevenschoen.putionew.UIUtils;
import com.stevenschoen.putionew.activities.FileDetailsActivity;
import com.stevenschoen.putionew.model.PutioRestInterface;
import com.stevenschoen.putionew.model.files.PutioFileData;
import com.stevenschoen.putionew.model.responses.BasePutioResponse;
import com.stevenschoen.putionew.model.responses.CachedFilesListResponse;
import com.stevenschoen.putionew.model.responses.FilesListResponse;
import com.stevenschoen.putionew.model.responses.FilesSearchResponse;

import java.util.ArrayList;
import java.util.List;

public final class Files extends Fragment implements SwipeRefreshLayout.OnRefreshListener {
	
	private static final int VIEWMODE_LIST = 1;
	private static final int VIEWMODE_LISTOREMPTY = 2;
	private static final int VIEWMODE_LOADING = 3;
	private static final int VIEWMODE_EMPTY = 4;
	private static final int VIEWMODE_LISTORLOADING = 5;
	private int viewMode = VIEWMODE_LIST;

    private int highlightFileId = -1;

    private boolean buttonUpFolderShown = false;

	private View buttonUpFolder;

    public interface Callbacks {
        public void onFileSelected(int id);
        public void onSomethingSelected();
    }
    
    private static Callbacks sDummyCallbacks = new Callbacks() {
        @Override
        public void onFileSelected(int id) { }
        @Override
        public void onSomethingSelected() { }
    };
	
    private Callbacks mCallbacks = sDummyCallbacks;

	private PutioUtils utils;

	private FilesAdapter adapter;
	private ArrayList<PutioFileLayout> fileLayouts;
	private ListView listview;
	private SwipeRefreshLayout swipeRefreshLayout;
	
	private View loadingView;
	private View emptyView;
	private View emptySubfolderView;
	
	private boolean hasUpdated = false;

	private ArrayList<PutioFileData> fileData;

	static class State {
		boolean isSearch;
        String searchQuery;
		int id;
		int parentId;
	}

	State state = new State();

	private int origId;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);

		try {
			state.id = savedInstanceState.getInt("currentFolderId");
			state.isSearch = savedInstanceState.getBoolean("isSearch");
            state.searchQuery = savedInstanceState.getString("searchQuery");
			state.parentId = savedInstanceState.getInt("parentId");
			origId = savedInstanceState.getInt("origId");
			fileLayouts = savedInstanceState.getParcelableArrayList("fileLayouts");
			fileData =  savedInstanceState.getParcelableArrayList("fileData");
			hasUpdated = savedInstanceState.getBoolean("hasUpdated");
		} catch (NullPointerException e) {
			state.id = 0;
			state.isSearch = false;
			state.parentId = 0;
			origId = 0;
			fileLayouts = new ArrayList<>();
			fileData = new ArrayList<>();
			hasUpdated = false;
		}

		this.utils = ((PutioApplication) getActivity().getApplication()).getPutioUtils();

		utils.getEventBus().register(this);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.files, container, false);
		
		listview = (ListView) view.findViewById(R.id.fileslist);
        if (!UIUtils.isTV(getActivity())) {
            swipeRefreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.filesSwipeRefresh);
            swipeRefreshLayout.setOnRefreshListener(this);
			swipeRefreshLayout.setColorSchemeResources(
					R.color.putio_accent,
					R.color.putio_accent_dark,
					R.color.putio_accent,
					R.color.putio_accent_dark);
		}
		
		adapter = new FilesAdapter(getActivity(), fileLayouts);
		listview.setAdapter(adapter);
		listview.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
		if (UIUtils.hasHoneycomb()) {
			listview.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
			listview.setMultiChoiceModeListener(new AbsListView.MultiChoiceModeListener() {
				@Override
				public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
					mode.setTitle(listview.getCheckedItemCount() + " selected");
					mode.getMenu().clear();
					if (listview.getCheckedItemCount() > 1) {
						mode.getMenuInflater().inflate(R.menu.context_files_multiple, mode.getMenu());
					} else {
						mode.getMenuInflater().inflate(R.menu.context_files, mode.getMenu());
					}
				}

				@Override
				public boolean onCreateActionMode(ActionMode mode, Menu menu) {
					if (listview.getCheckedItemCount() > 1) {
						mode.getMenuInflater().inflate(R.menu.context_files_multiple, mode.getMenu());
					} else {
						mode.getMenuInflater().inflate(R.menu.context_files, mode.getMenu());
					}
					return true;
				}

				@Override
				public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
					mode.setTitle(listview.getCheckedItemCount() + " selected");
					return true;
				}

				@Override
				public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
					SparseBooleanArray checkedPositionsBooleans = listview.getCheckedItemPositions();
					ArrayList<Integer> checkedPositions = new ArrayList<>();
					for (int i = 0; i < checkedPositionsBooleans.size(); i++) {
						if (checkedPositionsBooleans.valueAt(i)) {
							checkedPositions.add(checkedPositionsBooleans.keyAt(i));
						}
					}
					int[] checkedPositionsArray = new int[checkedPositions.size()];
					for (int i = 0; i < checkedPositions.size(); i++) {
						checkedPositionsArray[i] = checkedPositions.get(i);
					}

					listview.clearChoices();
					mode.finish();

					switch (item.getItemId()) {
						case R.id.context_download:
							initDownloadFiles(checkedPositionsArray);
							return true;
						case R.id.context_copydownloadlink:
							initCopyFileDownloadLink(checkedPositionsArray[0]);
							return true;
						case R.id.context_rename:
							initRenameFile(checkedPositionsArray[0]);
							return true;
						case R.id.context_delete:
							initDeleteFile(checkedPositionsArray);
							return true;
					}

					return false;
				}

				@Override
				public void onDestroyActionMode(ActionMode mode) { }
			});
		}
		if (UIUtils.isTablet(getActivity())) {
			listview.setVerticalFadingEdgeEnabled(true);
			listview.setScrollBarStyle(ScrollView.SCROLLBARS_OUTSIDE_INSET);
			if (UIUtils.hasHoneycomb()) {
				listview.setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_LEFT);
				listview.setFastScrollAlwaysVisible(true);
			}
		}
		listview.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> a, View view, int position, long id) {
				mCallbacks.onSomethingSelected();

				state.isSearch = false;

				PutioFileData file = fileData.get(position);
				if (!file.isFolder()) {
					if (!UIUtils.isTablet(getActivity())) {
						Intent detailsIntent = new Intent(getActivity(),
								FileDetailsActivity.class);
						detailsIntent.putExtra("fileData", file);
						startActivity(detailsIntent);
					} else {
						mCallbacks.onFileSelected(position);
					}
				} else {
					state.id = file.id;
					invalidateList();
				}
			}
		});

		buttonUpFolder = view.findViewById(R.id.files_up);
		buttonUpFolder.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				goBack();
			}
		});

		loadingView = view.findViewById(R.id.files_loading);
		emptyView = view.findViewById(R.id.files_empty);
		ImageButton buttonRefresh = (ImageButton) emptyView.findViewById(R.id.button_filesempty_refresh);
		buttonRefresh.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				invalidateList();
			}
		});
		emptySubfolderView = view.findViewById(R.id.files_empty_subfolder);

		if (savedInstanceState == null) {
			buttonUpFolder.post(new Runnable() {
				@Override
				public void run() {
                    buttonUpFolder.setTranslationY(buttonUpFolder.getHeight());
				}
			});
			invalidateList();
			setViewMode(VIEWMODE_LOADING);
		} else {
			addUpButtonIfNeeded();
			setViewMode(VIEWMODE_LISTORLOADING);
		}
		
		return view;
	}
	
	private void setViewMode(int mode) {
		switch (mode) {
		case VIEWMODE_LIST:
			listview.setVisibility(View.VISIBLE);
			loadingView.setVisibility(View.GONE);
			emptyView.setVisibility(View.GONE);
            emptySubfolderView.setVisibility(View.GONE);
			break;
		case VIEWMODE_LOADING:
			listview.setVisibility(View.INVISIBLE);
			loadingView.setVisibility(View.VISIBLE);
			emptyView.setVisibility(View.GONE);
            emptySubfolderView.setVisibility(View.GONE);
			break;
		case VIEWMODE_EMPTY:
			listview.setVisibility(View.INVISIBLE);
			loadingView.setVisibility(View.GONE);
			if (state.id == 0 && !state.isSearch) {
				emptyView.setVisibility(View.VISIBLE);
				emptySubfolderView.setVisibility(View.GONE);
			} else {
				emptyView.setVisibility(View.GONE);
				emptySubfolderView.setVisibility(View.VISIBLE);
			}
			break;
		case VIEWMODE_LISTOREMPTY:
			if (fileData == null || fileData.isEmpty()) {
				setViewMode(VIEWMODE_EMPTY);
			} else {
				setViewMode(VIEWMODE_LIST);
			}
			break;
		case VIEWMODE_LISTORLOADING:
			if ((fileData == null || fileData.isEmpty()) && !hasUpdated) {
				setViewMode(VIEWMODE_LOADING);
			} else {
				setViewMode(VIEWMODE_LISTOREMPTY);
			}
			break;
		}
		viewMode = mode;
	}
	
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        mCallbacks = (Callbacks) activity;
    }
    
    @Override
    public void onDetach() {
        super.onDetach();

        mCallbacks = sDummyCallbacks;
    }
	
	private void initDownloadFiles(final int... indeces) {
		PutioFileData[] files = new PutioFileData[indeces.length];
		for (int i = 0; i < indeces.length; i++) {
			files[i] = fileData.get(indeces[i]);
		}
		
		utils.downloadFile(getActivity(), PutioUtils.ACTION_NOTHING, files);
	}
	
	private void initCopyFileDownloadLink(int index) {
		utils.copyDownloadLink(getActivity(), fileData.get(index).id);
	}

	private void initRenameFile(final int index) {
		final Dialog renameDialog = PutioUtils.PutioDialog(getActivity(), getString(R.string.renametitle), R.layout.dialog_rename);
		renameDialog.show();
		
		final EditText textFileName = (EditText) renameDialog.findViewById(R.id.editText_fileName);
		textFileName.setText(fileData.get(index).name);
		
		ImageButton btnUndoName = (ImageButton) renameDialog.findViewById(R.id.button_undoName);
		btnUndoName.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				textFileName.setText(fileData.get(index).name);
			}
		});
		
		Button saveRename = (Button) renameDialog.findViewById(R.id.button_rename_save);
		saveRename.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				PutioFileData file = fileData.get(index);
				utils.getJobManager().addJobInBackground(new PutioRestInterface.PostRenameFileJob(
						utils, file.id, textFileName.getText().toString()));
				renameDialog.dismiss();
			}
		});
		
		Button cancelRename = (Button) renameDialog.findViewById(R.id.button_rename_cancel);
		cancelRename.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View arg0) {
				renameDialog.cancel();
			}
		});
	}
	
	private void initDeleteFile(int... indeces) {
		PutioFileData[] files = new PutioFileData[indeces.length];
		for (int i = 0; i < indeces.length; i++) {
			files[i] = fileData.get(indeces[i]);
		}
		utils.showDeleteFilesDialog(getActivity(), false, files);
	}
	
	public void invalidateList() {
		utils.getJobManager().addJobInBackground(new PutioRestInterface.GetFilesListJob(
				utils, getCurrentFolderId(), !UIUtils.isTV(getActivity())));
		
		if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(true);
	}
	
	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		super.onCreateOptionsMenu(menu, inflater);

        inflater.inflate(R.menu.search, menu);
        MenuItem buttonSearch = menu.findItem(R.id.menu_search);
        SearchManager searchManager = (SearchManager) getActivity().getSystemService(Context.SEARCH_SERVICE);
        SearchView searchView = (SearchView) buttonSearch.getActionView();
        searchView.setIconifiedByDefault(true);
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getActivity().getComponentName()));

        try {
            int searchPlateId = searchView.getContext().getResources().getIdentifier(
                    "android:id/search_plate", null, null);
            View searchPlate = searchView.findViewById(searchPlateId);
            searchPlate.setBackgroundResource(R.drawable.edit_text_search_putio);
        } catch (Exception e) {
//            SearchView couldn't be styled
        }
	}

    public void initSearch(String query) {
        state.searchQuery = query;

		utils.getJobManager().addJobInBackground(new PutioRestInterface.GetFilesSearchJob(
				utils, query));

		if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(true);
	}
	
	private void populateList(final List<PutioFileData> files, int newId, int parentId, int origIdBefore) {
        state.parentId = parentId;
		hasUpdated = true;
		int index = listview.getFirstVisiblePosition();
		View v = listview.getChildAt(0);
		int top = (v == null) ? 0 : v.getTop();

		addUpButtonIfNeeded();

		if (files == null) {
			setViewMode(VIEWMODE_EMPTY);
			return;
		}

		fileData.clear();
		fileData.addAll(files);
		
		setViewMode(VIEWMODE_LISTOREMPTY);

		fileLayouts.clear();
		if (isAdded()) {
			for (PutioFileData file : files) {
				Integer iconResource = PutioFileData.contentTypes.get(file.contentType);
				if (iconResource == null) iconResource = R.drawable.ic_putio_file;
				fileLayouts.add(new PutioFileLayout(
						file.name,
						getString(R.string.size_is, PutioUtils.humanReadableByteCount(
								file.size, false)),
						iconResource,
						file.icon
				));
			}
		}
		adapter.notifyDataSetChanged();

		if (newId != origIdBefore) {
			for (int i = 0; i < listview.getCount(); i++) {
				listview.setItemChecked(i, false);
			}
		}

		listview.setSelectionFromTop(index, top);
		
		if (highlightFileId != -1) {
			final int highlightPos = getIndexFromFileId(highlightFileId);
			
			listview.requestFocus();
            listview.smoothScrollToPosition(highlightPos);

            highlightFileId = -1;
		}
	}

	private void addUpButtonIfNeeded() {
		buttonUpFolder.post(new Runnable() {
			@Override
			public void run() {
                int hideY = buttonUpFolder.getHeight();
                RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams)
                        buttonUpFolder.getLayoutParams();
                hideY += params.bottomMargin;

				if (isInSubfolderOrSearch() && !buttonUpFolderShown) {
                    buttonUpFolder.animate()
                            .setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime))
                            .setInterpolator(new DecelerateInterpolator())
                            .translationY(0);
					listview.setPadding(0, 0, 0, hideY);
					buttonUpFolder.setEnabled(true);
					buttonUpFolder.setFocusable(true);

                    buttonUpFolderShown = true;
				} else if (!isInSubfolderOrSearch() && buttonUpFolderShown) {
                    buttonUpFolder.animate()
                            .setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime))
                            .setInterpolator(new AccelerateInterpolator())
                            .translationY(hideY);
					listview.setPadding(0, 0, 0, 0);
					buttonUpFolder.setEnabled(false);
					buttonUpFolder.setFocusable(false);

                    buttonUpFolderShown = false;
				}

				buttonUpFolder.setVisibility(View.VISIBLE);
			}
		});
	}
	
	public int getIndexFromFileId(int fileId) {
		for (int i = 0; i < fileData.size(); i++) {
			if (fileData.get(i).id == fileId) {
				return i;
			}
		}
		return -1;
	}
	
	public PutioFileData getFileAtId(int id) {
		return fileData.get(id);
	}
	
	public int getCurrentFolderId() {
		return state.id;
	}
	
	public boolean goBack() {
		if (isInSubfolderOrSearch()) {
			if (state.isSearch) {
				state.isSearch = false;
				state.id = 0;
			} else {
				state.id = state.parentId;
			}
			invalidateList();

			return true;
		} else {
			return false;
		}
	}
	
	public boolean isInSubfolderOrSearch() {
		return (state.id != 0 || state.isSearch);
	}
	
	public void highlightFile(int parentId, int id) {
		state.id = parentId;
        highlightFileId = id;
		invalidateList();
	}
	
	public void setFileChecked(int fileId, boolean checked) {
		listview.setItemChecked(getIndexFromFileId(fileId), checked);
	}

	public void onEventMainThread(FilesListResponse result) {
		if (result != null) {
			state.isSearch = false;
			populateList(result.getFiles(), result.getParent().id, result.getParent().parentId, origId);
			if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
		}
	}

	public void onEventMainThread(CachedFilesListResponse result) {
		if (result != null) {
			state.isSearch = false;
			populateList(result.getFiles(), result.getParent().id, result.getParent().parentId, origId);
		}
	}

	public void onEventMainThread(FilesSearchResponse result) {
		if (result != null && state.searchQuery.equals(result.getQuery())) {
			state.isSearch = true;
			populateList(result.getFiles(), -1, -2, -3);
			if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
		}
	}

	public void onEventMainThread(BasePutioResponse.FileChangingResponse result) {
		if (result.getStatus().equals("OK")) {
			invalidateList();
		}
	}
	
	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		
		outState.putInt("currentFolderId", state.id);
		outState.putInt("origId", origId);
		outState.putBoolean("isSearch", state.isSearch);
        outState.putString("searchQuery", state.searchQuery);
		outState.putInt("parentId", state.parentId);
		outState.putParcelableArrayList("fileLayouts", fileLayouts);
		outState.putParcelableArrayList("fileData", fileData);
		outState.putBoolean("hasUpdated", hasUpdated);
	}

	@Override
	public void onDestroy() {
		utils.getEventBus().unregister(this);

		super.onDestroy();
	}

	@Override
	public void onRefresh() {
		invalidateList();
	}
}