package com.couchbase.grocerysync;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import com.couchbase.lite.Database;
import com.couchbase.lite.Document;
import com.couchbase.lite.Emitter;
import com.couchbase.lite.LiveQuery;
import com.couchbase.lite.Manager;
import com.couchbase.lite.Mapper;
import com.couchbase.lite.QueryRow;
import com.couchbase.lite.SavedRevision;
import com.couchbase.lite.android.AndroidContext;
import com.couchbase.lite.replicator.Replication;
import com.couchbase.lite.util.Log;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.UUID;

public class MainActivity extends Activity implements Replication.ChangeListener,
        OnItemClickListener, OnItemLongClickListener {

    public static String TAG = "EEPOSIt";

    //constants

    public static final String DATABASE_NAME = "eeposit_db";
    public static final String designDocName = "eeposit-local1";
    public static final String byDateViewName = "byDate";


    public static final String SYNC_URL = "http://10.0.3.2:4984/eeposit_db";

    //splash screen
    protected SplashScreenDialog splashDialog;

    //main screen
    protected EditText input;
    protected Button   save;
    protected Button   delete;

    protected Document selectedDocument;
    protected Boolean editMode=false;
    Map<String, Object> newProperties;
    String selectedDocId;

    protected ListView itemListView;
    protected GrocerySyncArrayAdapter grocerySyncArrayAdapter;

    //couch internals
    protected static Manager manager;
    private Database database;
    private LiveQuery liveQuery;



    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        //connect items from layout
        input = (EditText)findViewById(R.id.addItemEditText1);
        itemListView = (ListView)findViewById(R.id.itemListView1);
        save=(Button)findViewById(R.id.save);
        delete=(Button)findViewById(R.id.delete);

        try {
            startCBLite();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "Error Initializing CBLIte, see logs for details", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error initializing CBLite", e);
        }

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (input.getText().toString()=="") {
                } else {
                     if (editMode==true) {
                         try {

                            Document doc=database.getDocument(selectedDocId);
                            // Toast.makeText(MainActivity.this,"selss---"+selectedDocId,Toast.LENGTH_LONG).show();
                             HashMap    newProperties= new HashMap<String, Object>(doc.getProperties());
                            newProperties.put("text",input.getText().toString());
                            doc.putProperties(newProperties);
                            grocerySyncArrayAdapter.notifyDataSetChanged();
                            editMode=false;
                            input.setText("");
                             save.setText("Save");
                        } catch (Exception e) {
                            Toast.makeText(getApplicationContext(), "Error updating database, see logs for details", Toast.LENGTH_LONG).show();
                            Log.e(TAG, "Error updating database", e);
                        }

                    }
                    else{


                         try {
                            createGroceryItem(input.getText().toString());
                             grocerySyncArrayAdapter.notifyDataSetChanged();
                         } catch (Exception e) {
                             Toast.makeText(getApplicationContext(), "Error updating database, see logs for details", Toast.LENGTH_LONG).show();
                             Log.e(TAG, "Error updating database", e);
                         }
                     }
                }
            }
        });
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Document doc=database.getDocument(selectedDocId);
                try {
                    doc.delete();
                }catch (Exception e){}

            }
        });





    }


    protected void onDestroy() {
        if(manager != null) {
            manager.close();
        }
        super.onDestroy();
    }

    protected void startCBLite() throws Exception {

        Manager.enableLogging(TAG, Log.VERBOSE);
        Manager.enableLogging(Log.TAG, Log.VERBOSE);
        Manager.enableLogging(Log.TAG_SYNC_ASYNC_TASK, Log.VERBOSE);
        Manager.enableLogging(Log.TAG_SYNC, Log.VERBOSE);
        Manager.enableLogging(Log.TAG_QUERY, Log.VERBOSE);
        Manager.enableLogging(Log.TAG_VIEW, Log.VERBOSE);
        Manager.enableLogging(Log.TAG_DATABASE, Log.VERBOSE);

        manager = new Manager(new AndroidContext(this), Manager.DEFAULT_OPTIONS);

        //install a view definition needed by the application
        database = manager.getDatabase(DATABASE_NAME);
        com.couchbase.lite.View viewItemsByDate = database.getView(String.format("%s/%s", designDocName, byDateViewName));
        viewItemsByDate.setMap(new Mapper() {
            @Override
            public void map(Map<String, Object> document, Emitter emitter) {
                Object createdAt = document.get("created_at");
                if (createdAt != null) {
                    emitter.emit(createdAt.toString(), null);
                }
            }
        }, "1.0");

        initItemListAdapter();

        startLiveQuery(viewItemsByDate);

        startSync();

    }

    private void startSync() {

        URL syncUrl;
        try {
            syncUrl = new URL(SYNC_URL);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        Replication pullReplication = database.createPullReplication(syncUrl);
        pullReplication.setContinuous(true);

        Replication pushReplication = database.createPushReplication(syncUrl);
        pushReplication.setContinuous(true);

        pullReplication.start();
        pushReplication.start();

        pullReplication.addChangeListener(this);
        pushReplication.addChangeListener(this);

    }

    private void startLiveQuery(com.couchbase.lite.View view) throws Exception {

        final ProgressDialog progressDialog = showLoadingSpinner();

        if (liveQuery == null) {

            liveQuery = view.createQuery().toLiveQuery();

            liveQuery.addChangeListener(new LiveQuery.ChangeListener() {
                public void changed(final LiveQuery.ChangeEvent event) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            grocerySyncArrayAdapter.clear();
                            for (Iterator<QueryRow> it = event.getRows(); it.hasNext();) {
                                grocerySyncArrayAdapter.add(it.next());
                            }
                            grocerySyncArrayAdapter.notifyDataSetChanged();
                            progressDialog.dismiss();
                        }
                    });
                }
            });

            liveQuery.start();

        }

    }

    private void initItemListAdapter() {
        grocerySyncArrayAdapter = new GrocerySyncArrayAdapter(
                getApplicationContext(),
                R.layout.grocery_list_item,
                R.id.label,
                new ArrayList<QueryRow>()
        );
        itemListView.setAdapter(grocerySyncArrayAdapter);
        itemListView.setOnItemClickListener(MainActivity.this);
        itemListView.setOnItemLongClickListener(MainActivity.this);
    }


    private ProgressDialog showLoadingSpinner() {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Loading");
        progress.setMessage("Wait while loading...");
        progress.show();
        return progress;
    }





    /**
     * Handle click on item in list
     */
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {

        QueryRow row = (QueryRow) adapterView.getItemAtPosition(position);
        Document document = row.getDocument();

        HashMap  newProperties= new HashMap<String, Object>(document.getProperties());
        String groceryItemText = (newProperties.get("text").toString());

        input.setText(groceryItemText);
        save.setText("Update");

        editMode=true;

        boolean checked = ((Boolean) newProperties.get("check")).booleanValue();
        newProperties.put("check", !checked);

        try {
            document.putProperties(newProperties);
            grocerySyncArrayAdapter.notifyDataSetChanged();
            selectedDocId=document.getId();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "Error updating database, see logs for details", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error updating database", e);
        }

    }

    /**
     * Handle long-click on item in list
     */
    public boolean onItemLongClick(AdapterView<?> adapterView, View view, int position, long id) {

        QueryRow row = (QueryRow) adapterView.getItemAtPosition(position);
        final Document clickedDocument = row.getDocument();
        String itemText = (String) clickedDocument.getCurrentRevision().getProperty("text");

        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        AlertDialog alert = builder.setTitle("Delete Item?")
                .setMessage("Are you sure you want to delete \"" + itemText + "\"?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        try {
                            clickedDocument.delete();
                        } catch (Exception e) {
                            Toast.makeText(getApplicationContext(), "Error deleting document, see logs for details", Toast.LENGTH_LONG).show();
                            Log.e(TAG, "Error deleting document", e);
                        }
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        // Handle Cancel
                    }
                })
                .create();

        alert.show();

        return true;
    }


    /**
     * Removes the Dialog that displays the splash screen
     */
    protected void removeSplashScreen() {
        if (splashDialog != null) {
            splashDialog.dismiss();
            splashDialog = null;
        }
    }

    /**
     * Shows the splash screen over the full Activity
     */
    private void showSplashScreen() {
        splashDialog = new SplashScreenDialog(this);
        splashDialog.show();
    }

    /**
     * Add settings item to the menu
     */
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, 0, 0, "Settings");
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * Launch the settings activity
     */
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 0:
                startActivity(new Intent(this, GrocerySyncPreferencesActivity.class));
                return true;
        }
        return false;
    }

    private Document createGroceryItem(String text) throws Exception {

        SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

        UUID uuid = UUID.randomUUID();
        Calendar calendar = GregorianCalendar.getInstance();
        long currentTime = calendar.getTimeInMillis();
        String currentTimeString = dateFormatter.format(calendar.getTime());

        String id = currentTime + "-" + uuid.toString();

        Document document = database.createDocument();

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("_id", id);
        properties.put("text", text);
        properties.put("check", Boolean.FALSE);
        properties.put("created_at", currentTimeString);
        document.putProperties(properties);


        Log.d(TAG, "Created new grocery item with id: %s", document.getId());

        return document;
    }



    @Override
    public void changed(Replication.ChangeEvent event) {

        Replication replication = event.getSource();
        Log.d(TAG, "Replication : " + replication + " changed.");
        if (!replication.isRunning()) {
            String msg = String.format("Replicator %s not running", replication);
            Log.d(TAG, msg);
        }
        else {
            int processed = replication.getCompletedChangesCount();
            int total = replication.getChangesCount();
            String msg = String.format("Replicator processed %d / %d", processed, total);
            Log.d(TAG, msg);
        }

        if (event.getError() != null) {
            showError("Sync error", event.getError());
        }

    }

    public void showError(final String errorMessage, final Throwable throwable) {

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String msg = String.format("%s: %s", errorMessage, throwable);
                Log.e(TAG, msg, throwable);
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        });

    }

}
