/*
 *  Copyright 2018 Information and Computational Sciences,
 *  The James Hutton Institute.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package uk.ac.hutton.android.germinatescan.activity;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.*;
import android.view.View.OnKeyListener;
import android.widget.*;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.zxing.integration.android.*;

import java.io.*;
import java.util.*;

import androidx.annotation.NonNull;
import androidx.core.app.*;
import androidx.core.content.*;
import androidx.recyclerview.widget.*;

import uk.ac.hutton.android.germinatescan.*;
import uk.ac.hutton.android.germinatescan.adapter.RecyclerGridAdapter;
import uk.ac.hutton.android.germinatescan.database.*;
import uk.ac.hutton.android.germinatescan.database.manager.*;
import uk.ac.hutton.android.germinatescan.database.writer.*;
import uk.ac.hutton.android.germinatescan.util.*;
import uk.ac.hutton.android.germinatescan.util.FileUtils.*;
import uk.ac.hutton.android.germinatescan.util.barcodehandler.*;

/**
 * {@link BarcodeReader} is the main activity of Germinate Scan. It handles all the barcode reading
 *
 * @author Sebastian Raubach
 */
public class BarcodeReader extends DrawerActivity implements LocationUtils.LocationChangeListener, OnKeyListener, View.OnFocusChangeListener, TextToSpeech.OnInitListener
{
	/* CONSTANTS */
	public static final  String DELIMITER                               = "\t";
	private static final int    REQUEST_PHOTO                           = 100;
	private static final int    REQUEST_SHOW_IMAGES                     = 101;
	private static final int    REQUEST_CODE_STORAGE_PERMISSIONS        = 102;
	private static final int    REQUEST_CODE_CAMERA_PICTURE_PERMISSIONS = 103;
	private static final int    REQUEST_CODE_CAMERA_SCAN_PERMISSIONS    = 104;
	private static final int    REQUEST_CODE_INTRO                      = 105;
	private static final int    REQUEST_CODE_IMPORT_PHENOTYPES          = 106;

	/* INSTANCE */
	public static BarcodeReader             INSTANCE;
	public        boolean                   resetDatabase = false;
	/* FIELDS */
	private       File                      photo;
	private       Location                  location;
	private       TextToSpeech              tts;
	private       ArrayDeque<String>        ttsQueue      = new ArrayDeque<>();
	private       boolean                   ttsInit       = false;
	private       PreferenceUtils           prefs;
	private       GridSpacingItemDecoration decoration;
	private       String                    toSpeak       = null;

	/* COLLECTIONS */
	private List<String> preloadedPhenotypes;

	/* VIEWS */
	private RecyclerView gridView;
	private EditText     hiddenInput;
	private View         welcomeMessage;

	/* ADAPTERS */
	private RecyclerGridAdapter adapter;

	private BarcodeHandler handler;
	private BarcodeManager barcodeManager;
	private ImageManager   imageManager;
	private Dataset        dataset;

	private Barcode barcodeForImage = null;

	private boolean override = false;

	/**
	 * Returns the number of barcodes per row
	 *
	 * @return The number of barcodes per row
	 */
	public static int getDeprecatedNrOfBarcodes()
	{
		/* Load persistent url of web service */
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(BarcodeReader.INSTANCE);
		return PreferenceUtils.getInt(preferences, PreferenceUtils.PREF_BARCODES, PreferenceUtils.DEFAULT_PREF_NR_BARCODES);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		INSTANCE = this;

		/* Make sure the default preferences are set */
		prefs = new PreferenceUtils(this);
		prefs.setDefaults();

		DatasetManager datasetManager = new DatasetManager(this, -1);
		List<Dataset> datasets = datasetManager.getAll();
		Dataset d;

		/* If there are no datasets, create an initial one */
		if (CollectionUtils.isEmpty(datasets))
		{
			d = new Dataset(getString(R.string.dataset_initial));
			d.setCreatedOn(new Date());
			d.setUpdatedOn(new Date());
			datasetManager.add(d);
			prefs.putLong(PreferenceUtils.PREFS_SELECTED_DATASET_ID, d.getId());
		}
		else
		{
			long datasetId = prefs.getLong(PreferenceUtils.PREFS_SELECTED_DATASET_ID, -1);
			d = new DatasetManager(this, datasetId).getById(datasetId);
		}

		DatabaseHandler oldHandler = new DatabaseHandler(this);
		List<Barcode> oldBarcodes = oldHandler.getAllBarcodes();

		/* If there are barcodes from previous version (pre-datasets), store them in the new dataset that was created above */
		if (!CollectionUtils.isEmpty(oldBarcodes))
		{
			BarcodeManager barcodeManager = new BarcodeManager(this, 1);
			ImageManager imageManager = new ImageManager(this, 1);
			d.setBarcodesPerRow(getDeprecatedNrOfBarcodes());
			new DatasetManager(this, d.getId()).update(d);

			/* For each of the old barcodes */
			for (Barcode barcode : oldBarcodes)
			{
				barcode.setId(null);
				/* Add it to the new database */
				barcodeManager.add(barcode);

				/* Then check all its images */
				List<Image> images = barcode.getImages();
				if (!CollectionUtils.isEmpty(images))
				{
					for (Image image : images)
					{
						/* Move them from the generic output folder to the dataset specific one */
						File source = new File(image.getPath());
						File target = new File(FileUtils.getPathToReferenceFolder(this, dataset.getId(), ReferenceFolder.images), source.getName());
						source.renameTo(target);

						/* Update the path */
						image.setPath(target.getAbsolutePath());

						image.setId(null);
						/* Then add it to the new database as well */
						imageManager.add(barcode, image);
					}
				}
			}

			/* Delete the old database */
			oldHandler.deleteDatabase();
		}

		String versionNumber = getString(R.string.app_unknown_version_number);
		try
		{
			versionNumber = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		}
		catch (PackageManager.NameNotFoundException e)
		{
		}

		/* Track the version of the app */
		GoogleAnalyticsUtils.track(this, getTracker(), FirebaseAnalytics.Event.APP_OPEN, getString(R.string.ga_event_category_version), versionNumber);

		/* Request to keep the screen on */
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
//        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
//        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
//        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
	}

	private void init()
	{
		boolean showIntro = !prefs.getBoolean(PreferenceUtils.PREFS_EULA_ACCEPTED, false);

		if (showIntro)
		{
			/* Show the intro */
			startActivityForResult(new Intent(getApplicationContext(), IntroductionActivity.class), REQUEST_CODE_INTRO);
		}
		else
		{
			long datasetId = prefs.getLong(PreferenceUtils.PREFS_SELECTED_DATASET_ID, -1);
			Dataset dataset = new DatasetManager(this, datasetId).getById(datasetId);

			if (dataset.getBarcodesPerRow() < 1)
			{
				startActivityForResult(new Intent(getApplicationContext(), BarcodeSelectionActivity.class), REQUEST_CODE_INTRO);
				return;
			}

			/* Else check if this is an override call. If so, recreate the activity */
			if (override)
			{
				Intent intent = getIntent();
				finish();
				startActivity(intent);
			}
			/* Else if the data source changed, update the content */
			else if (this.dataset == null || !dataset.equals(this.dataset))
			{
				this.dataset = dataset;

				updateContent();
			}

			/* Remember the new data source and disable override */
			this.dataset = dataset;
			override = false;
		}
	}

	private void updateContent()
	{
		barcodeManager = new BarcodeManager(this, dataset.getId());
		imageManager = new ImageManager(this, dataset.getId());

//		List<String> strings = Arrays.asList("PLT - 7284", "1", "38.5", "PLT - 7284", "2", "60.5", "PLT - 7284", "3", "59.5", "PLT - 7284", "4", "68.0", "PLT - 7284", "5", "80.0", "PLT - 7284", "1", "31.5", "PLT - 7284", "2", "55.0", "PLT - 7284", "3", "57.0", "PLT - 7284", "4", "66.5", "PLT - 7284", "5", "77.5", "PLT - 7284", "1", "36.0", "PLT - 7284", "2", "58.5", "PLT - 7284", "3", "57.5", "PLT - 7284", "4", "67.5", "PLT - 7284", "5", "79.5", "PLT - 7285", "1", "53.0", "PLT - 7285", "2", "76.0", "PLT - 7285", "3", "76.0", "PLT - 7285", "4", "85.0", "PLT - 7285", "5", "97.0", "PLT - 7285", "1", "47.5", "PLT - 7285", "2", "71.0", "PLT - 7285", "3", "71.5", "PLT - 7285", "4", "80.5", "PLT - 7285", "5", "93.0", "PLT - 7285", "1", "45.5", "PLT - 7285", "2", "68.0", "PLT - 7285", "3", "69.0", "PLT - 7285", "4", "79.0", "PLT - 7285", "5", "92.5", "PLT - 7286", "1", "59.5", "PLT - 7286", "2", "83.0", "PLT - 7286", "3", "80.5", "PLT - 7286", "4", "89.5", "PLT - 7286", "5", "102.0", "PLT - 7286", "1", "52.5", "PLT - 7286", "2", "75.5", "PLT - 7286", "3", "71.5", "PLT - 7286", "4", "80.0", "PLT - 7286", "5", "93.0", "PLT - 7286", "1", "51.5", "PLT - 7286", "2", "74.0", "PLT - 7286", "3", "70.0", "PLT - 7286", "4", "79.0", "PLT - 7286", "5", "91.5", "PLT - 7287", "1", "46.5", "PLT - 7287", "2", "70.0", "PLT - 7287", "3", "67.5", "PLT - 7287", "4", "76.0", "PLT - 7287", "5", "88.5", "PLT - 7287", "1", "47.0", "PLT - 7287", "2", "70.0", "PLT - 7287", "3", "68.0", "PLT - 7287", "4", "76.5", "PLT - 7287", "5", "90.0", "PLT - 7287", "1", "43.0", "PLT - 7287", "2", "67.0", "PLT - 7287", "3", "65.5", "PLT - 7287", "4", "75.5", "PLT - 7287", "5", "88.0", "PLT - 7288", "1", "51.0", "PLT - 7288", "2", "66.5", "PLT - 7288", "3", "65.5", "PLT - 7288", "4", "72.5", "PLT - 7288", "5", "82.0", "PLT - 7288", "1", "51.0", "PLT - 7288", "2", "68.5", "PLT - 7288", "3", "64.5", "PLT - 7288", "4", "71.0", "PLT - 7288", "5", "81.5", "PLT - 7288", "1", "46.0", "PLT - 7288", "2", "63.0", "PLT - 7288", "3", "60.0", "PLT - 7288", "4", "68.5", "PLT - 7288", "5", "79.5", "PLT - 7289", "1", "43.0", "PLT - 7289", "2", "66.5", "PLT - 7289", "3", "65.0", "PLT - 7289", "4", "73.0", "PLT - 7289", "5", "87.5", "PLT - 7289", "1", "43.0", "PLT - 7289", "2", "65.0", "PLT - 7289", "3", "64.0", "PLT - 7289", "4", "72.0", "PLT - 7289", "5", "86.5", "PLT - 7289", "1", "41.0", "PLT - 7289", "2", "63.5", "PLT - 7289", "3", "60.0", "PLT - 7289", "4", "67.0", "PLT - 7289", "5", "79.0", "PLT - 7290", "1", "56.0", "PLT - 7290", "2", "77.5", "PLT - 7290", "3", "75.0", "PLT - 7290", "4", "83.0", "PLT - 7290", "5", "96.5", "PLT - 7290", "1", "43.0", "PLT - 7290", "2", "63.0", "PLT - 7290", "3", "61.0", "PLT - 7290", "4", "70.5", "PLT - 7290", "5", "82.5", "PLT - 7290", "1", "45.0", "PLT - 7290", "2", "66.0", "PLT - 7290", "3", "64.0", "PLT - 7290", "4", "73.0", "PLT - 7290", "5", "86.0", "PLT - 7291", "1", "59.5", "PLT - 7291", "2", "81.5", "PLT - 7291", "3", "87.0", "PLT - 7291", "4", "95.0", "PLT - 7291", "5", "106.5", "PLT - 7291", "1", "55.5", "PLT - 7291", "2", "77.5", "PLT - 7291", "3", "83.0", "PLT - 7291", "4", "90.5", "PLT - 7291", "5", "101.0", "PLT - 7291", "1", "45.0", "PLT - 7291", "2", "63.5", "PLT - 7291", "3", "68.5");
//		List<Barcode> barcodes = new ArrayList<>();
//
//		for(String s : strings)
//			barcodes.add(new Barcode(s));
//
//		dataset.setPreloadedPhenotypes(Arrays.asList("1", "2", "3", "4", "5"));
//		dataset.setCurrentPhenotype(0);
//		dataset.setBarcodesPerRow(3);
//		new DatasetManager(this, dataset.getId()).update(dataset);
//
//		barcodeManager.add(barcodes);

		List<Barcode> items = barcodeManager.getAll();
		adapter = new RecyclerGridAdapter(this, dataset.getId(), items);

		welcomeMessage = findViewById(R.id.welcome_message);

		updateWelcomeMessageVisibility();

		int valueInPixels = (int) getResources().getDimension(R.dimen.activity_vertical_margin) / 2;

		gridView = findViewById(R.id.grid_scanned_items);
		gridView.setLayoutManager(new GridLayoutManager(this, dataset.getBarcodesPerRow()));
		decoration = new GridSpacingItemDecoration(dataset.getBarcodesPerRow(), valueInPixels, valueInPixels, valueInPixels);
		gridView.addItemDecoration(decoration);

		hiddenInput = findViewById(R.id.input);
		hiddenInput.setOnKeyListener(this);
		hiddenInput.requestFocus();

		FloatingActionButton floatingActionButton = findViewById(R.id.floating_action_main);
		floatingActionButton.setOnClickListener(v -> scanBarcodePrePermission());

		/* Redirect every focus from the list view to the input text */
		gridView.setOnFocusChangeListener(this);
		gridView.setAdapter(adapter);
		gridView.scrollToPosition(adapter.getItemCount() - 1);

		addAdapterDataObserver();

		/* Show "What's new" if this is a new version */
		showWhatsNew();

		updateHandler();
	}

	protected void showWhatsNew()
	{
		try
		{
			// Get the versionCode of the Package, which must be different (incremented) in each release on the market in the AndroidManifest.xml
			final PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_ACTIVITIES);

			int lastVersionCode = prefs.getInt(PreferenceUtils.PREFS_LAST_VERSION, -1);

			/* If this is the first start, show nothing just remember the version number */
			if (lastVersionCode == -1)
			{
				prefs.putInt(PreferenceUtils.PREFS_LAST_VERSION, packageInfo.versionCode);
			}
			/* Else show what's new if version numbers differ */
			else
			{
				if (packageInfo.versionCode != lastVersionCode)
				{
					startActivity(new Intent(getApplicationContext(), ChangelogActivity.class));
				}
			}

		}
		catch (PackageManager.NameNotFoundException e)
		{
			e.printStackTrace();
		}
	}

	@Override
	public void onStart()
	{
		super.onStart();

		acquireTTS();
	}

	@Override
	protected void onResume()
	{
		super.onResume();

		init();
	}

	private void acquireTTS()
	{
		/* Check if we need to enable TTS */
		boolean enableReadBack = prefs.getBoolean(PreferenceUtils.PREFS_VOICE_FEEDBACK, false);

		if (enableReadBack && tts == null)
		{
			tts = new TextToSpeech(this, this);
		}
		else if (!enableReadBack && tts != null)
		{
			ttsQueue.clear();
			ttsInit = false;
			tts.shutdown();
			tts = null;
		}
	}

	@Override
	public void onStop()
	{
		/* Shut down TTS if it is enabled */
		if (tts != null)
		{
			ttsQueue.clear();
			ttsInit = false;
			tts.shutdown();
			tts = null;
		}
		super.onStop();
	}

	@Override
	public void onLocationChanged(Location location)
	{
		this.location = location;
	}

	@Override
	protected Integer getLayoutId()
	{
		return R.layout.activity_main;
	}

	@Override
	protected Integer getToolbarId()
	{
		return R.id.toolbar;
	}

	@Override
	protected View getSnackbarParentView()
	{
		return findViewById(R.id.fab_parent);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		getMenuInflater().inflate(R.menu.main_menu, menu);

		menu.findItem(R.id.menu_load_phenotypes)
				.setVisible(dataset != null && dataset.getBarcodesPerRow() == 3);
		menu.findItem(R.id.menu_ignore_duplicates)
				.setVisible(dataset != null && dataset.getBarcodesPerRow() == 1)
				.setChecked(dataset.getIgnoreDuplicates());

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId())
		{
			case android.R.id.home:
				super.onOptionsItemSelected(item);
				break;

			case R.id.menu_take_picture:
				/* Take a picture */
				takePicture(null);
				break;

			case R.id.menu_clear_database:
				/* Clear the database */
				resetDatabaseDialog();
				GoogleAnalyticsUtils.track(this, getTracker(), FirebaseAnalytics.Event.SELECT_CONTENT, getString(R.string.ga_event_category_database), getString(R.string.ga_event_action_reset));
				break;

			case R.id.menu_export_database:

				exportDatabase(true, false);
				break;

			case R.id.menu_scan_barcode:
				scanBarcodePrePermission();
				break;

			case R.id.menu_ignore_duplicates:
				dataset.setIgnoreDuplicates(!dataset.getIgnoreDuplicates());
				item.setChecked(!item.isChecked());
				new DatasetManager(this, dataset.getId()).update(dataset);
				break;

			case R.id.menu_load_phenotypes:
				if (adapter.getItemCount() > 0)
				{
					new AlertDialog.Builder(this)
							.setTitle(R.string.dialog_title_phenotype_warning)
							.setMessage(R.string.dialog_message_phenotype_warning)
							.setPositiveButton(R.string.general_yes, (dialog, which) -> {
								Intent intent = new Intent(BarcodeReader.this, PhenotypeActivity.class);
								intent.putExtra(PhenotypeActivity.EXTRA_DATASET_ID, dataset.getId());
								startActivityForResult(intent, REQUEST_CODE_IMPORT_PHENOTYPES);
							})
							.setNegativeButton(R.string.general_no, null)
							.show();
				}
				else
				{
					Intent intent = new Intent(BarcodeReader.this, PhenotypeActivity.class);
					intent.putExtra(PhenotypeActivity.EXTRA_DATASET_ID, dataset.getId());
					startActivityForResult(intent, REQUEST_CODE_IMPORT_PHENOTYPES);
				}
				break;
		}

		return true;
	}

	private void scanBarcodePrePermission()
	{
		if (ContextCompat.checkSelfPermission(BarcodeReader.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
		{
			/* Request the permission */
			if (!deniedPermissions.contains(Manifest.permission.CAMERA))
				ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA_SCAN_PERMISSIONS);

			return;
		}

		scanBarcode();
	}

	private void scanBarcode()
	{
		/* Add the third-party barcode scanner */
		IntentIntegrator integrator = new IntentIntegrator(this);
//      integrator.setOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
		integrator.initiateScan();
		GoogleAnalyticsUtils.track(this, getTracker(), FirebaseAnalytics.Event.VIEW_ITEM, getString(R.string.ga_event_category_scan), getString(R.string.ga_event_action_scan_camera));
	}

	/**
	 * Exports the database to a local file
	 */
	public void exportDatabase(boolean cancelable, final boolean reset)
	{
		/* Export the database to a file */
		if (ContextCompat.checkSelfPermission(BarcodeReader.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
		{
			/* Request the permission */
			if (!deniedPermissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
				ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE_STORAGE_PERMISSIONS);

			return;
		}

		DialogUtils.UserOption[] options = {
				new DialogUtils.UserOption(getString(R.string.label_export_option_save), R.drawable.export_option_save),
				new DialogUtils.UserOption(getString(R.string.label_export_option_send), R.drawable.export_option_share)
		};

		DialogUtils.showOptions(this, R.string.dialog_title_export_option, options, cancelable, index -> {
			/* Write the file in any case */
			DatabaseWriter writer;

			try
			{
				if (dataset.isPhenotypingMode())
				{
					writer = new PhenotypingModeDatabaseWriter(BarcodeReader.this, DELIMITER);
					write(writer, index, reset);
				}
				else if (prefs.getBoolean(PreferenceUtils.PREFS_EXPORT_MATRIX_FORMAT, false))
				{
					writer = new MatrixDatabaseWriter(BarcodeReader.this, DELIMITER);
					write(writer, index, reset);
				}
				else
				{
					writer = new RowDatabaseWriter(BarcodeReader.this, DELIMITER);
					write(writer, index, reset);
				}
			}
			catch (IOException e)
			{
				try
				{
					// Try again with the basic exporter if one of the others failed
					write(new RowDatabaseWriter(BarcodeReader.this, DELIMITER), index, reset);
				}
				catch (IOException e1)
				{
					e1.printStackTrace();
				}

				e.printStackTrace();
				SnackbarUtils.showError(getSnackbarParentView(), BarcodeReader.this.getString(R.string.toast_exception, "File operation failed"), Snackbar.LENGTH_LONG);
			}
		});
	}

	private void write(DatabaseWriter writer, int index, boolean reset) throws IOException
	{
		File exportedFile = writer.write();

		if (exportedFile == null)
		{
			SnackbarUtils.showError(getSnackbarParentView(), BarcodeReader.this.getString(R.string.toast_exception, "File operation failed"), Snackbar.LENGTH_LONG);
		}
		else
		{
			switch (index)
			{
				case 0:
					SnackbarUtils.showSuccess(getSnackbarParentView(), BarcodeReader.this.getString(R.string.toast_file_saved_to, exportedFile.getAbsolutePath()), Snackbar.LENGTH_LONG);
					break;
				case 1:
					/* Ask Android to share it for us */
					ShareCompat.IntentBuilder builder = ShareCompat.IntentBuilder.from(BarcodeReader.this);
					/* Use the provider to make the file available to the other app (Android requirement) */
					String providerName = getPackageName() + ".provider";
					Uri uri = FileProvider.getUriForFile(BarcodeReader.this, providerName, exportedFile);

					builder.setType("text/html")
							.setChooserTitle(R.string.intent_title_send_file)
							.setStream(uri)
							.setSubject(BarcodeReader.this.getString(R.string.email_subject_export))
							.setText(BarcodeReader.this.getString(R.string.email_message_export));

					startActivity(builder.getIntent());
					break;
			}
		}

		if (reset)
			resetDatabase();

		GoogleAnalyticsUtils.track(this, getTracker(), FirebaseAnalytics.Event.BEGIN_CHECKOUT, getString(R.string.ga_event_category_database), getString(R.string.ga_event_action_export));
	}

	/**
	 * Starts an intent to take a picture
	 */
	private void takePicture()
	{
		/* Prepare the intent */
		Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

		/* Place where to store camera taken picture */
		photo = FileUtils.createFile(this, dataset.getId(), ReferenceFolder.images, FileExtension.jpg);

		Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", photo);
		takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
		takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

		startActivityForResult(takePictureIntent, REQUEST_PHOTO);

		GoogleAnalyticsUtils.track(this, getTracker(), FirebaseAnalytics.Event.VIEW_ITEM, getString(R.string.ga_event_category_other), getString(R.string.ga_event_action_take_picture));
	}

	public void takePicture(Barcode barcode)
	{
		barcodeForImage = barcode;

		if (ContextCompat.checkSelfPermission(BarcodeReader.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
				|| ContextCompat.checkSelfPermission(BarcodeReader.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
		{
			List<String> permission = new ArrayList<>();

			/* Request the permission */
			if (!deniedPermissions.contains(Manifest.permission.CAMERA))
				permission.add(Manifest.permission.CAMERA);
			if (!deniedPermissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
				permission.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

			if (permission.size() > 0)
				ActivityCompat.requestPermissions(this, permission.toArray(new String[permission.size()]), REQUEST_CODE_CAMERA_PICTURE_PERMISSIONS);
		}
		else
		{
			takePicture();
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, final Intent data)
	{
		acquireTTS();

		IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
		/* See if it's the camera barcode scanner */
		if (scanResult != null && resultCode == Activity.RESULT_OK)
		{
			/* Set the text */
			hiddenInput.setText(scanResult.getContents());

			/* Create an enter key event */
			KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER);
			onKey(hiddenInput, KeyEvent.KEYCODE_ENTER, event);
		}
		else if (requestCode == REQUEST_SHOW_IMAGES && resultCode == Activity.RESULT_OK)
		{
			/* Images have been deleted, get all items from the database again */
			updateGrid();
		}
		else if (requestCode == REQUEST_PHOTO && resultCode == Activity.RESULT_OK)
		{
			final ImagePreferences[] imgPrefs;
			final CharSequence[] items;
			final boolean[] selectedItems;
			/* Depending on if there are barcodes or not, create the options */
			if (adapter.getItemCount() < 1 || barcodeForImage != null)
				imgPrefs = new ImagePreferences[]{ImagePreferences.TAG_TIMESTAMP, ImagePreferences.TAG_LOCATION};
			else
				imgPrefs = new ImagePreferences[]{ImagePreferences.TAG_TIMESTAMP, ImagePreferences.TAG_LOCATION, ImagePreferences.USE_BARCODE_AS_FILENAME, ImagePreferences.USE_FIRST_BARCODE_IN_ROW_AS_FILENAME};

			items = new CharSequence[imgPrefs.length];
			for (int i = 0; i < imgPrefs.length; i++)
			{
				items[i] = imgPrefs[i].getDisplayName();
			}

			selectedItems = prefs.getImagePreferences(imgPrefs);

			/* Show an alert dialog */
			new AlertDialog.Builder(this).setTitle(R.string.dialog_title_save_image).setMultiChoiceItems(items, selectedItems, (dialog, which, isChecked) -> {
				/* Remember which option the user checked */
				if (isChecked && imgPrefs.length == 4 && (which == 2 || which == 3))
				{
					int other = 5 - which;

					selectedItems[other] = false;
					prefs.putBoolean(imgPrefs[other].getPreferenceKey(), false);

					final AlertDialog alert = (AlertDialog) dialog;
					final ListView list = alert.getListView();
					list.setItemChecked(other, false);
				}

				selectedItems[which] = isChecked;
				prefs.putBoolean(imgPrefs[which].getPreferenceKey(), isChecked);
			}).setPositiveButton(R.string.general_save, (dialog, which) -> {
				/*  */
				Barcode associatedBarcode = null;
				if (barcodeForImage != null)
				{
					associatedBarcode = barcodeForImage;
					barcodeForImage = null;
				}
				if (selectedItems.length > 2 && selectedItems[2])
				{
					List<Barcode> rows = adapter.getItems();
					associatedBarcode = rows.get(rows.size() - 1);
				}
				else if (selectedItems.length > 3 && selectedItems[3])
				{
					List<Barcode> rows = adapter.getItems();
					associatedBarcode = rows.get(rows.size() - 1);
					associatedBarcode = adapter.getItemsInRow(associatedBarcode).get(0);
				}

				if (associatedBarcode != null)
				{
					movePhoto(associatedBarcode);

					Image image = new Image(null, photo.getAbsolutePath());

					associatedBarcode.addImage(image);
					imageManager.add(associatedBarcode, image);

					updateGrid();
				}
				else
				{
					/* Geotag the image */
					GeoUtils.geoTag(photo.getAbsolutePath(), location, new Date(System.currentTimeMillis()), null);

					SnackbarUtils.showSuccess(getSnackbarParentView(), getString(R.string.toast_photo_saved_to, photo.getAbsolutePath()), Snackbar.LENGTH_LONG);
				}
			}).setNegativeButton(R.string.general_cancel, (dialog, which) -> photo.delete()).show();

			/* Notify the Android gallery */
			Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
			Uri contentUri = Uri.fromFile(photo);
			mediaScanIntent.setData(contentUri);
			this.sendBroadcast(mediaScanIntent);

			super.onActivityResult(requestCode, resultCode, data);
		}
		else if (requestCode == REQUEST_DATA_SOURCE && resultCode == Activity.RESULT_OK)
		{
			override = true;
		}
		/* Return from preferences */
		else if (requestCode == REQUEST_PREFS && resultCode == Activity.RESULT_OK && resetDatabase)
		{
			resetDatabase = false;
			resetDatabase();
//			setCodeFormats();
		}
		else if (requestCode == REQUEST_CODE_IMPORT_PHENOTYPES && resultCode == Activity.RESULT_OK)
		{
			resetDatabase();

			DatasetManager datasetManager = new DatasetManager(this, dataset.getId());
			dataset = datasetManager.getById(dataset.getId());
			preloadedPhenotypes = dataset.getPreloadedPhenotypes();

			updateHandler();
		}
		else if (requestCode == REQUEST_CODE_INTRO)
		{
			if (!prefs.getBoolean(PreferenceUtils.PREFS_EULA_ACCEPTED, false))
			{
				/* EULA not accepted, finish */
				finish();
			}

			if (data != null && data.getExtras() != null)
			{
				int nrOfBarcodes = data.getExtras().getInt(BarcodeSelectionActivity.EXTRA_NR_OF_BARCODES, PreferenceUtils.DEFAULT_PREF_NR_BARCODES);
				long datasetId = prefs.getLong(PreferenceUtils.PREFS_SELECTED_DATASET_ID, -1L);

				if (datasetId >= 0)
				{
					DatasetManager datasetManager = new DatasetManager(this, datasetId);
					Dataset dataset = datasetManager.getById(datasetId);
					dataset.setBarcodesPerRow(nrOfBarcodes);
					datasetManager.update(dataset);
				}
			}

			/* User has seen intro after already having used the app */
			if (barcodeManager != null && !CollectionUtils.isEmpty(barcodeManager.getAll()))
			{
				final int prefItems = adapter.getItemsInRow(adapter.getItem(0)).size();
				/* And the user selected a different number of barcodes than what they've currently got */
				if (prefItems != dataset.getBarcodesPerRow())
				{
					/* Give them the option to either export their data and switch or stay on the current number of barcodes */
					String[] items = new String[]{getString(R.string.dialog_list_export_and_reset), getString(R.string.dialog_list_cancel)};

					LayoutInflater factory = LayoutInflater.from(this);
					View content = factory.inflate(R.layout.helper_dialog_message_list, null);

					TextView tv = content.findViewById(R.id.dialog_title);
					tv.setText(R.string.dialog_message_after_intro_reset);
					ListView lv = content.findViewById(R.id.dialog_list);
					lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items));

					final AlertDialog dialog = new AlertDialog.Builder(this)
							.setTitle(R.string.dialog_title_after_intro_reset)
							.setView(content)
							.setCancelable(false)
							.create();

					lv.setOnItemClickListener((parent, view, position, id) -> {
						if (position == 0)
						{
							exportDatabase(false, true);
							resetDatabase = true;
						}
						else if (position == 1)
						{
							DatasetManager datasetManager = new DatasetManager(BarcodeReader.this, dataset.getId());
							datasetManager.update(dataset.setBarcodesPerRow(prefItems));
							updateGrid();
						}

						dialog.dismiss();
					});

					dialog.show();
				}
			}
		}
		else
		{
			super.onActivityResult(requestCode, resultCode, data);
		}
	}

	private void movePhoto(Barcode barcode)
	{
		String filename = barcode.getBarcode();

		boolean tagDate = prefs.getBoolean(ImagePreferences.TAG_TIMESTAMP.getPreferenceKey(), true);
		boolean tagLocation = prefs.getBoolean(ImagePreferences.TAG_LOCATION.getPreferenceKey(), true);

		String prefix = null;
		Date date = null;
		Location location = null;

		if (tagDate)
		{
			prefix = FileUtils.getDate();
			date = new Date(System.currentTimeMillis());
		}
		if (tagLocation)
		{
			location = this.location;
		}

		File newFile = FileUtils.createFile(BarcodeReader.this, prefix, dataset.getId(), ReferenceFolder.images, FileExtension.jpg, filename);

		photo.renameTo(newFile);

		/* Geotag the image */
		GeoUtils.geoTag(newFile.getAbsolutePath(), location, date, barcode.getStringForImageTag());

		SnackbarUtils.showSuccess(getSnackbarParentView(), getString(R.string.toast_photo_saved_to, newFile.getAbsolutePath()), Snackbar.LENGTH_LONG);

		photo = newFile;
	}

	private void updateGrid()
	{
		adapter = new RecyclerGridAdapter(this, dataset.getId(), barcodeManager.getAll());
		gridView.setLayoutManager(new GridLayoutManager(this, dataset.getBarcodesPerRow()));
		gridView.setAdapter(adapter);

		if (decoration != null)
			gridView.removeItemDecoration(decoration);

		int valueInPixels = (int) getResources().getDimension(R.dimen.activity_vertical_margin) / 2;
		decoration = new GridSpacingItemDecoration(dataset.getBarcodesPerRow(), valueInPixels, valueInPixels, valueInPixels);
		gridView.addItemDecoration(decoration);

		gridView.scrollToPosition(adapter.getItemCount() - 1);
		addAdapterDataObserver();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
	{
		switch (requestCode)
		{
			case REQUEST_CODE_STORAGE_PERMISSIONS:
				if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
				{
					/* Permission Granted */
					exportDatabase(true, resetDatabase);
					resetDatabase = false;
				}
				else
				{
					SnackbarUtils.showError(getSnackbarParentView(), getString(R.string.toast_permission_missing_storage), Snackbar.LENGTH_LONG);
				}

				break;
			case REQUEST_CODE_CAMERA_PICTURE_PERMISSIONS:
			{
				if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
				{
					/* Permission granted */
					takePicture();
				}
				else
				{
					SnackbarUtils.showError(getSnackbarParentView(), getString(R.string.toast_permission_missing_camera), Snackbar.LENGTH_LONG);
				}

				break;
			}
			case REQUEST_CODE_CAMERA_SCAN_PERMISSIONS:
			{
				if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
				{
					/* Permission granted */
					scanBarcode();
				}
				else
				{
					SnackbarUtils.showError(getSnackbarParentView(), getString(R.string.toast_permission_missing_camera), Snackbar.LENGTH_LONG);
				}

				break;
			}

			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}

	/**
	 * Asks the user to confirm the decision to clear the database
	 */
	private void resetDatabaseDialog()
	{
		new AlertDialog.Builder(this)
				.setPositiveButton(getString(R.string.general_yes), (dialogInterface, i) -> resetDatabase())
				.setNegativeButton(getString(R.string.general_no), null)
				.setTitle(R.string.dialog_title_reset_database)
				.setMessage(getString(R.string.dialog_message_reset_database_short))
				.show();
	}

	/**
	 * Clears the database
	 */
	private void resetDatabase()
	{
		new DatasetManager(this, dataset.getId()).reset();
		preloadedPhenotypes = null;

		updateGrid();

		invalidateOptionsMenu();

		updateWelcomeMessageVisibility();

		updateHandler();
	}

	private void updateHandler()
	{
		boolean restored = false;
		if (preloadedPhenotypes == null)
		{
			preloadedPhenotypes = dataset.getPreloadedPhenotypes();
			restored = true;
		}

		if (dataset.isPhenotypingMode())
		{
			String currentPlant = null;

			if (restored)
			{
				if (adapter.getItemCount() > 0)
					currentPlant = adapter.getItemsInRow(adapter.getItem(adapter.getItemCount() - 1)).get(0).getBarcode();
			}
			else if (adapter.getItemCount() < 1)
			{
				new AlertDialog.Builder(this)
						.setTitle(R.string.dialog_title_traits_importer)
						.setMessage(R.string.dialog_message_traits_imported)
						.setPositiveButton(R.string.general_close, null)
						.show();
			}

			handler = new PhenotypeBarcodeHandler(this, dataset, preloadedPhenotypes, currentPlant)
			{
				@Override
				protected Barcode getCurrentPlant()
				{
					if (adapter.getItemCount() > 0)
						return adapter.getItemsInRow(adapter.getItem(adapter.getItemCount() - 1)).get(0);
					else
						return null;
				}

				@Override
				public void deleteBarcode()
				{
					if (adapter.getItemCount() > 0)
					{
						/* Delete the current cell */
						int index = adapter.getCurrentColumnIndex();
						int currentPhenotype = dataset.getCurrentPhenotype();

						if (index == 2)
						{
							// If it's a value, remove it, but keep the rest.
							BarcodeReader.this.deleteItem(adapter.getItem(adapter.getItemCount() - 1));
							currentPhenotype--;
							dataset.setCurrentPhenotype(currentPhenotype);
						}
						else if (index == 1)
						{
							// If it's a trait, remove everything.
							BarcodeReader.this.deleteItem(adapter.getItem(adapter.getItemCount() - 1));
							BarcodeReader.this.deleteItem(adapter.getItem(adapter.getItemCount() - 1));
							// If it's not the first one, remove the value of the previous trait.
							if (currentPhenotype != 0)
							{
								BarcodeReader.this.deleteItem(adapter.getItem(adapter.getItemCount() - 1));
								if (adapter.getItemCount() > 0)
									currentPhenotype--;
								dataset.setCurrentPhenotype(currentPhenotype);
							}
						}
						else if (index == 0)
						{
							// If it's a plant, check if it's the first trait index, then just remove, else, do same as index 1
							if (currentPhenotype == 0)
							{
								BarcodeReader.this.deleteItem(adapter.getItem(adapter.getItemCount() - 1));
								if (adapter.getItemCount() > 0)
									currentPhenotype--;
								dataset.setCurrentPhenotype(currentPhenotype);
							}
							else
							{
								BarcodeReader.this.deleteItem(adapter.getItem(adapter.getItemCount() - 1));
								BarcodeReader.this.deleteItem(adapter.getItem(adapter.getItemCount() - 1));
								currentPhenotype--;
								dataset.setCurrentPhenotype(currentPhenotype);
							}
						}


						GoogleAnalyticsUtils.track(context, context.getTracker(), FirebaseAnalytics.Event.REMOVE_FROM_CART, context.getString(R.string.ga_event_category_scan), context.getString(R.string.ga_event_action_delete));
					}
				}

				@Override
				public void deleteRow()
				{
					deleteBarcode();
				}

				@Override
				protected void onPlantComplete()
				{
					toSpeak = getString(R.string.tts_plant_complete, getCurrentPlant().getBarcode());
					SnackbarUtils.showSuccess(getSnackbarParentView(), toSpeak, Snackbar.LENGTH_LONG);
				}
			};
		}
		else
		{
			handler = new DefaultBarcodeHandler(this, dataset)
			{
				@Override
				public void deleteBarcode()
				{
					if (adapter.getItemCount() > 0)
					{
						/* Delete the current cell */
						BarcodeReader.this.deleteItem(adapter.getItem(adapter.getItemCount() - 1));

						GoogleAnalyticsUtils.track(context, context.getTracker(), FirebaseAnalytics.Event.REMOVE_FROM_CART, context.getString(R.string.ga_event_category_scan), context.getString(R.string.ga_event_action_delete));
					}
				}

				@Override
				public void deleteRow()
				{
					if (adapter.getItemCount() > 0)
					{
						/* Delete the current row */
						Barcode lastBarcode = adapter.getItem(adapter.getItemCount() - 1);
						BarcodeReader.this.deleteRow(lastBarcode);

						GoogleAnalyticsUtils.track(context, context.getTracker(), FirebaseAnalytics.Event.REMOVE_FROM_CART, context.getString(R.string.ga_event_category_scan), context.getString(R.string.ga_event_action_delete));
					}
				}
			};
		}
	}

	@Override
	public boolean onKey(View view, int keyCode, KeyEvent event)
	{
		/* We only want to process key down events */
		if (event.getAction() != KeyEvent.ACTION_DOWN)
		{
			return false;
		}

		if (keyCode == KeyEvent.KEYCODE_ENTER)
		{
			String input = hiddenInput.getText().toString();

			if (StringUtils.isEmpty(input))
			{
				return true;
			}

			List<Barcode> barcodes = handler.handle(input, location, adapter.getCurrentColumnIndex());

			if (!CollectionUtils.isEmpty(barcodes))
			{
				for (Barcode barcode : barcodes)
				{
					adapter.add(barcode);

					GoogleAnalyticsUtils.track(this, getTracker(), FirebaseAnalytics.Event.ADD_TO_CART, getString(R.string.ga_event_category_scan), getString(R.string.ga_event_action_scan));

					if (barcode.isSpeak())
						speak(barcode.getBarcode());

					hiddenInput.setText(null);
				}
			}
			else if (dataset.getIgnoreDuplicates() && barcodes == null)
			{
				speak(getString(R.string.tts_plant_duplicate, input));
			}

			if (toSpeak != null)
			{
				speak(toSpeak);
				toSpeak = null;
			}

			hiddenInput.setText(null);
			hiddenInput.requestFocusFromTouch();
			return true;
		}
		return false;
	}

	@Override
	public void onFocusChange(View view, boolean hasFocus)
	{
		if (hasFocus)
		{
			hiddenInput.requestFocusFromTouch();
		}
	}

	private void deleteRow(Barcode item)
	{
		List<Barcode> toDelete = adapter.getItemsInRow(item);

		adapter.removeAll(toDelete);
	}

	private void deleteItem(Barcode item)
	{
		adapter.remove(item);
	}

	public void showImages(View view, Barcode item)
	{
		Intent i = new Intent(getApplicationContext(), ImageDetailActivity.class);
		i.putExtra(ImageDetailActivity.EXTRA_ROW, item);
		i.putExtra(ImageDetailActivity.EXTRA_DATASET_ID, dataset.getBarcodesPerRow());

		Bundle bundle = ActivityOptions.makeScaleUpAnimation(view, (int) view.getX(), (int) view.getY(), view.getWidth(), view.getHeight()).toBundle();
		ActivityCompat.startActivityForResult(this, i, REQUEST_SHOW_IMAGES, bundle);
	}

	private void updateWelcomeMessageVisibility()
	{
		if (adapter.getItemCount() < 1)
		{
			welcomeMessage.setVisibility(View.VISIBLE);
		}
		else
		{
			welcomeMessage.setVisibility(View.GONE);
		}
	}

	@Override
	public void onInit(int status)
	{
		if (status != TextToSpeech.SUCCESS)
		{
			Log.e("TTS", "Initilization Failed!");
			ttsInit = false;

		}
		else
		{
			ttsInit = true;
			for (int i = 0; i < ttsQueue.size(); i++)
			{
				speak(ttsQueue.pollFirst());
			}
		}
	}

	private void addAdapterDataObserver()
	{
		adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver()
		{
			private void update()
			{
				updateWelcomeMessageVisibility();
			}

			@Override
			public void onItemRangeChanged(int positionStart, int itemCount)
			{
				update();
			}

			@Override
			public void onItemRangeInserted(int positionStart, int itemCount)
			{
				update();
				gridView.smoothScrollToPosition(positionStart);
			}

			@Override
			public void onItemRangeRemoved(int positionStart, int itemCount)
			{
				update();
			}

			@Override
			public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount)
			{
				update();
			}

			@Override
			public void onChanged()
			{
				update();
			}
		});
	}

	/**
	 * Uses TTS (text-to-speech) to read the message back to the user
	 *
	 * @param message The message
	 */
	private void speak(final String message)
	{
		if (tts != null)
		{
			if (!ttsInit)
			{
				ttsQueue.addLast(message);
			}
			else
			{
				reallySpeak(message);
			}
		}
	}

	private void reallySpeak(String message)
	{
		if (tts != null)
			tts.speak(message, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString());
	}
}
