package net.osmand.plus.search;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;

import net.osmand.AndroidUtils;
import net.osmand.Location;
import net.osmand.ResultMatcher;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.data.FavouritePoint;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.plus.OsmAndLocationProvider.OsmAndCompassListener;
import net.osmand.plus.OsmAndLocationProvider.OsmAndLocationListener;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.dashboard.DashLocationFragment;
import net.osmand.plus.helpers.SearchHistoryHelper;
import net.osmand.plus.helpers.SearchHistoryHelper.HistoryEntry;
import net.osmand.plus.resources.RegionAddressRepository;
import net.osmand.search.SearchUICore;
import net.osmand.search.SearchUICore.SearchResultCollection;
import net.osmand.search.SearchUICore.SearchResultMatcher;
import net.osmand.search.core.ObjectType;
import net.osmand.search.core.SearchCoreAPI;
import net.osmand.search.core.SearchCoreFactory;
import net.osmand.search.core.SearchPhrase;
import net.osmand.search.core.SearchResult;
import net.osmand.search.core.SearchSettings;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class QuickSearchDialogFragment extends DialogFragment implements OsmAndCompassListener, OsmAndLocationListener {

	public static final String TAG = "QuickSearchDialogFragment";
	private static final String QUICK_SEARCH_QUERY_KEY = "quick_search_query_key";
	private ListView listView;
	private SearchListAdapter listAdapter;
	private EditText searchEditText;
	private ProgressBar progressBar;
	private ImageButton clearButton;

	private SearchUICore searchUICore;
	private String searchQuery = "";
	private SearchResultCollection resultCollection;

	private net.osmand.Location location = null;
	private Float heading = null;

	public static final int SEARCH_FAVORITE_API_PRIORITY = 3;
	public static final int SEARCH_FAVORITE_OBJECT_PRIORITY = 10;
	public static final int SEARCH_HISTORY_API_PRIORITY = 3;
	public static final int SEARCH_HISTORY_OBJECT_PRIORITY = 10;


	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		boolean isLightTheme = getMyApplication().getSettings().OSMAND_THEME.get() == OsmandSettings.OSMAND_LIGHT_THEME;
		int themeId = isLightTheme ? R.style.OsmandLightTheme : R.style.OsmandDarkTheme;
		setStyle(STYLE_NO_FRAME, themeId);
	}

	@Override
	@SuppressLint("PrivateResource")
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
							 Bundle savedInstanceState) {
		final MapActivity mapActivity = getMapActivity();
		final View view = inflater.inflate(R.layout.search_dialog_fragment, container, false);

		if (savedInstanceState != null) {
			searchQuery = savedInstanceState.getString(QUICK_SEARCH_QUERY_KEY);
		}
		if (searchQuery == null) {
			searchQuery = getArguments().getString(QUICK_SEARCH_QUERY_KEY);
		}
		if (searchQuery == null)
			searchQuery = "";

		Toolbar toolbar = (Toolbar) view.findViewById(R.id.toolbar);
		toolbar.setNavigationIcon(R.drawable.abc_ic_ab_back_mtrl_am_alpha);
		toolbar.setNavigationContentDescription(R.string.access_shared_string_navigate_up);
		toolbar.setNavigationOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				dismiss();
			}
		});

		setupSearch(mapActivity);

		listView = (ListView) view.findViewById(android.R.id.list);
		listAdapter = new SearchListAdapter(getMyApplication(), getActivity());
		listView.setAdapter(listAdapter);
		listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				SearchListItem item = listAdapter.getItem(position);
				if (item instanceof SearchMoreListItem) {
					((SearchMoreListItem) item).getOnClickListener().onClick(view);
				} else {
					SearchResult sr = item.getSearchResult();

					boolean updateEditText = true;
					if (sr.objectType == ObjectType.POI
							|| sr.objectType == ObjectType.LOCATION
							|| sr.objectType == ObjectType.HOUSE
							|| sr.objectType == ObjectType.FAVORITE
							|| sr.objectType == ObjectType.RECENT_OBJ
							|| sr.objectType == ObjectType.WPT
							|| sr.objectType == ObjectType.STREET_INTERSECTION) {

						updateEditText = false;
						dismiss();
						if (sr.location != null) {
							showOnMap(sr);
						}
					}
					completeQueryWithObject(item.getSearchResult(), updateEditText);
				}
			}
		});

		searchEditText = (EditText) view.findViewById(R.id.searchEditText);
		searchEditText.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				String newQueryText = s.toString();
				if (!searchQuery.equalsIgnoreCase(newQueryText)) {
					searchQuery = newQueryText;
					runSearch();
				}
			}
		});

		progressBar = (ProgressBar) view.findViewById(R.id.searchProgressBar);
		clearButton = (ImageButton) view.findViewById(R.id.clearButton);
		clearButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (searchEditText.getText().length() > 0) {
					String newText = searchUICore.getPhrase().getTextWithoutLastWord();
					searchEditText.setText(newText);
					searchEditText.setSelection(newText.length());
				}
			}
		});

		searchEditText.requestFocus();
		AndroidUtils.softKeyboardDelayed(searchEditText);
		runSearch();

		return view;
	}

	private void setupSearch(final MapActivity mapActivity) {

		final OsmandApplication app = mapActivity.getMyApplication();

		// Setup search core
		String locale = app.getSettings().MAP_PREFERRED_LOCALE.get();

		Collection<RegionAddressRepository> regionAddressRepositories = app.getResourceManager().getAddressRepositories();
		BinaryMapIndexReader[] binaryMapIndexReaderArray = new BinaryMapIndexReader[regionAddressRepositories.size()];
		int i = 0;
		for (RegionAddressRepository rep : regionAddressRepositories) {
			binaryMapIndexReaderArray[i++] = rep.getFile();
		}
		searchUICore = new SearchUICore(app.getPoiTypes(), locale, binaryMapIndexReaderArray);

		/*
		List<BinaryMapIndexReader> files = new ArrayList<>();
		File file = new File(Environment.getExternalStorageDirectory() + "/osmand");
		if (file.exists() && file.listFiles() != null) {
			for (File obf : file.listFiles()) {
				if (!obf.isDirectory() && obf.getName().endsWith(".obf")) {
					try {
						BinaryMapIndexReader bmir = new BinaryMapIndexReader(new RandomAccessFile(obf, "r"), obf);
						files.add(bmir);
					} catch (Exception e1) {
						e1.printStackTrace();
					}
				}
			}
		}

		searchUICore = new SearchUICore(app.getPoiTypes(), locale, files.toArray(new BinaryMapIndexReader[files.size()]));
		*/

		LatLon centerLatLon = mapActivity.getMapView().getCurrentRotatedTileBox().getCenterLatLon();
		SearchSettings settings = searchUICore.getPhrase().getSettings().setOriginalLocation(
				new LatLon(centerLatLon.getLatitude(), centerLatLon.getLongitude()));
		settings = settings.setLang(locale);
		searchUICore.updateSettings(settings);
		searchUICore.setOnResultsComplete(new Runnable() {
			@Override
			public void run() {
				app.runInUIThread(new Runnable() {
					@Override
					public void run() {
						hideProgressBar();
						//updateSearchResult(searchUICore.getCurrentSearchResult(), true);
					}
				});
			}
		});

		// Setup favorites search api
		searchUICore.registerAPI(new SearchCoreFactory.SearchBaseAPI() {

			@Override
			public boolean search(SearchPhrase phrase, SearchResultMatcher resultMatcher) {
				List<FavouritePoint> favList = getMyApplication().getFavorites().getFavouritePoints();
				for (FavouritePoint point : favList) {
					SearchResult sr = new SearchResult(phrase);
					sr.localeName = point.getName();
					sr.object = point;
					sr.priority = SEARCH_FAVORITE_OBJECT_PRIORITY;
					sr.objectType = ObjectType.FAVORITE;
					sr.location = new LatLon(point.getLatitude(), point.getLongitude());
					sr.preferredZoom = 17;
					if (phrase.getLastWord().length() <= 1 && phrase.isNoSelectedType()) {
						resultMatcher.publish(sr);
					} else if (phrase.getNameStringMatcher().matches(sr.localeName)) {
						resultMatcher.publish(sr);
					}
				}
				return true;
			}

			@Override
			public int getSearchPriority(SearchPhrase p) {
				if(!p.isNoSelectedType()) {
					return -1;
				}
				return SEARCH_FAVORITE_API_PRIORITY;
			}
		});

		// Setup history search api
		searchUICore.registerAPI(new SearchCoreFactory.SearchBaseAPI() {
			@Override
			public boolean search(SearchPhrase phrase, SearchResultMatcher resultMatcher) {
				SearchHistoryHelper helper = SearchHistoryHelper.getInstance((OsmandApplication) getActivity()
						.getApplicationContext());
				List<HistoryEntry> points = helper.getHistoryEntries();
				for (HistoryEntry point : points) {
					SearchResult sr = new SearchResult(phrase);
					sr.localeName = point.getName().getName();
					sr.object = point;
					sr.priority = SEARCH_HISTORY_OBJECT_PRIORITY;
					sr.objectType = ObjectType.RECENT_OBJ;
					sr.location = new LatLon(point.getLat(), point.getLon());
					sr.preferredZoom = 17;
					if (phrase.getLastWord().length() <= 1 && phrase.isNoSelectedType()) {
						resultMatcher.publish(sr);
					} else if (phrase.getNameStringMatcher().matches(sr.localeName)) {
						resultMatcher.publish(sr);
					}
				}
				return true;
			}

			@Override
			public int getSearchPriority(SearchPhrase p) {
				if(!p.isNoSelectedType()) {
					return -1;
				}
				return SEARCH_HISTORY_API_PRIORITY;
			}
		});
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		setShowsDialog(true);
		final boolean isLightContent = getMyApplication().getSettings().isLightContent();
		final int colorId = isLightContent ? R.color.bg_color_light : R.color.bg_color_dark;
		listView.setBackgroundColor(ContextCompat.getColor(getActivity(), colorId));
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		outState.putString(QUICK_SEARCH_QUERY_KEY, searchQuery);
	}

	@Override
	public void onResume() {
		super.onResume();
		int screenOrientation = DashLocationFragment.getScreenOrientation(getActivity());
		listAdapter.setScreenOrientation(screenOrientation);
		OsmandApplication app = getMyApplication();
		app.getLocationProvider().addCompassListener(this);
		app.getLocationProvider().addLocationListener(this);
		location = app.getLocationProvider().getLastKnownLocation();
		updateLocation(location);

		if (!Algorithms.isEmpty(searchQuery)) {
			searchEditText.setText(searchQuery);
			searchEditText.setSelection(searchQuery.length());
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		OsmandApplication app = getMyApplication();
		app.getLocationProvider().removeLocationListener(this);
		app.getLocationProvider().removeCompassListener(this);
	}

	private void showProgressBar() {
		clearButton.setVisibility(View.GONE);
		progressBar.setVisibility(View.VISIBLE);
	}

	private void hideProgressBar() {
		clearButton.setVisibility(View.VISIBLE);
		progressBar.setVisibility(View.GONE);
	}

	private void runSearch() {
		runSearch(searchQuery);
	}

	private void runSearch(String text) {
		showProgressBar();
		SearchSettings settings = searchUICore.getPhrase().getSettings();
		if(settings.getRadiusLevel() != 1){
			searchUICore.updateSettings(settings.setRadiusLevel(1));
		}
		SearchResultCollection c = runCoreSearch(text);
		updateSearchResult(c, false, false);
	}

	private SearchResultCollection runCoreSearch(String text) {
		showProgressBar();
		return searchUICore.search(text, new ResultMatcher<SearchResult>() {

			SearchResultCollection regionResultCollection = null;
			SearchCoreAPI regionResultApi = null;
			List<SearchResult> results = new ArrayList<>();

			@Override
			public boolean publish(SearchResult object) {

				switch (object.objectType) {
					case SEARCH_API_FINISHED:
						final SearchCoreAPI searchApi = (SearchCoreAPI) object.object;

						final List<SearchResult> apiResults;
						final SearchPhrase phrase = object.requiredSearchPhrase;
						final SearchCoreAPI regionApi = regionResultApi;
						final SearchResultCollection regionCollection = regionResultCollection;

						final boolean hasRegionCollection = (searchApi == regionApi && regionCollection != null);
						if (hasRegionCollection) {
							apiResults = regionCollection.getCurrentSearchResults();
						} else {
							apiResults = results;
							searchUICore.sortSearchResults(phrase, apiResults);
						}

						regionResultApi = null;
						regionResultCollection = null;
						results = new ArrayList<>();

						getMyApplication().runInUIThread(new Runnable() {
							@Override
							public void run() {
								boolean appended = false;
								if (resultCollection == null || resultCollection.getPhrase() != phrase) {
									resultCollection = new SearchResultCollection(apiResults, phrase);
								} else {
									resultCollection.getCurrentSearchResults().addAll(apiResults);
									appended = true;
								}
								if (!hasRegionCollection) {
									updateSearchResult(resultCollection, true, appended);
								}
							}
						});
						break;
					case SEARCH_API_REGION_FINISHED:
						regionResultApi = (SearchCoreAPI) object.object;

						final List<SearchResult> regionResults = new ArrayList<>(results);
						final SearchPhrase regionPhrase = object.requiredSearchPhrase;
						searchUICore.sortSearchResults(regionPhrase, regionResults);

						getMyApplication().runInUIThread(new Runnable() {
							@Override
							public void run() {
								boolean appended = resultCollection != null && resultCollection.getPhrase() == regionPhrase;
								regionResultCollection = new SearchResultCollection(regionResults, regionPhrase);
								if (appended) {
									List<SearchResult> res = new ArrayList<>(resultCollection.getCurrentSearchResults());
									res.addAll(regionResults);
									SearchResultCollection resCollection = new SearchResultCollection(res, regionPhrase);
									updateSearchResult(resCollection, true, true);
								} else {
									updateSearchResult(regionResultCollection, true, false);
								}
							}
						});
						break;
					default:
						results.add(object);
				}

				return false;
			}

			@Override
			public boolean isCancelled() {
				return false;
			}
		});
	}

	private void completeQueryWithObject(SearchResult sr, boolean updateEditText) {
		searchUICore.selectSearchResult(sr);
		String txt = searchUICore.getPhrase().getText(true);
		if (updateEditText) {
			searchQuery = txt;
			searchEditText.setText(txt);
			searchEditText.setSelection(txt.length());
		}
		runCoreSearch(txt);
	}

	private void updateSearchResult(SearchResultCollection res, boolean addMore, boolean appended) {

		OsmandApplication app = getMyApplication();

		List<SearchListItem> rows = new ArrayList<>();
		if (res.getCurrentSearchResults().size() > 0) {
			if (addMore) {
				SearchMoreListItem moreListItem = new SearchMoreListItem(app, "Results " + res.getCurrentSearchResults().size() + ", radius " + res.getPhrase().getRadiusLevel() +
						" (show more...)", new OnClickListener() {
					@Override
					public void onClick(View v) {
						SearchSettings settings = searchUICore.getPhrase().getSettings();
						searchUICore.updateSettings(settings.setRadiusLevel(settings.getRadiusLevel() + 1));
						runCoreSearch(searchQuery);
						updateSearchResult(new SearchResultCollection(), false, false);
					}
				});
				rows.add(moreListItem);
			}
			for (final SearchResult sr : res.getCurrentSearchResults()) {
				rows.add(new SearchListItem(app, sr));
			}
		}
		updateListAdapter(rows, appended);
	}

	private void updateListAdapter(List<SearchListItem> listItems, boolean appended) {
		listAdapter.setListItems(listItems);
		if (listAdapter.getCount() > 0 && !appended) {
			listView.setSelection(0);
		}
	}

	private void showOnMap(SearchResult searchResult) {
		if (searchResult.location != null) {
			PointDescription pointDescription = null;
			Object object = null;
			switch (searchResult.objectType) {
				case POI:
					object = searchResult.object;
					pointDescription = getMapActivity().getMapLayers().getPoiMapLayer().getObjectName(object);
					break;
			}
			getMyApplication().getSettings().setMapLocationToShow(
					searchResult.location.getLatitude(), searchResult.location.getLongitude(),
					searchResult.preferredZoom, pointDescription, true, object);

			MapActivity.launchMapActivityMoveToTop(getActivity());
		}
	}

	public static boolean showInstance(final MapActivity mapActivity, final String searchQuery) {
		try {

			if (mapActivity.isActivityDestroyed()) {
				return false;
			}

			final OsmandApplication app = mapActivity.getMyApplication();
			if (app.isApplicationInitializing()) {
				new AsyncTask<Void, Void, Void>() {

					private ProgressDialog dlg;

					@Override
					protected void onPreExecute() {
						dlg = new ProgressDialog(mapActivity);
						dlg.setTitle("");
						dlg.setMessage(mapActivity.getString(R.string.wait_current_task_finished));
						dlg.setCanceledOnTouchOutside(false);
						dlg.show();
					}

					@Override
					protected Void doInBackground(Void... params) {
						while (app.isApplicationInitializing()) {
							try {
								Thread.sleep(50);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
						return null;
					}

					@Override
					protected void onPostExecute(Void aVoid) {
						dlg.hide();
						showInternal(mapActivity, searchQuery);
					}
				}.execute();

			} else {
				showInternal(mapActivity, searchQuery);
			}

			return true;

		} catch (RuntimeException e) {
			return false;
		}
	}

	private static void showInternal(MapActivity mapActivity, String searchQuery) {
		Bundle bundle = new Bundle();
		bundle.putString(QUICK_SEARCH_QUERY_KEY, searchQuery);
		QuickSearchDialogFragment fragment = new QuickSearchDialogFragment();
		fragment.setArguments(bundle);
		fragment.show(mapActivity.getSupportFragmentManager(), TAG);
	}

	private MapActivity getMapActivity() {
		return (MapActivity) getActivity();
	}

	private OsmandApplication getMyApplication() {
		return (OsmandApplication) getActivity().getApplication();
	}

	@Override
	public void updateCompassValue(final float value) {
		// 99 in next line used to one-time initialize arrows (with reference vs. fixed-north direction)
		// on non-compass devices
		float lastHeading = heading != null ? heading : 99;
		heading = value;
		if (Math.abs(MapUtils.degreesDiff(lastHeading, heading)) > 5) {
			final Location location = this.location;
			getMyApplication().runInUIThread(new Runnable() {
				@Override
				public void run() {
					updateLocationUI(location, value);
				}
			});
		} else {
			heading = lastHeading;
		}
	}

	@Override
	public void updateLocation(final Location location) {
		this.location = location;
		final Float heading = this.heading;
		getMyApplication().runInUIThread(new Runnable() {
			@Override
			public void run() {
				updateLocationUI(location, heading);
			}
		});
	}

	private void updateLocationUI(Location location, Float heading) {
		LatLon latLon = null;
		if (location != null) {
			latLon = new LatLon(location.getLatitude(), location.getLongitude());
		}
		listAdapter.setLocation(latLon);
		listAdapter.setHeading(heading);
		listAdapter.notifyDataSetChanged();
	}
}