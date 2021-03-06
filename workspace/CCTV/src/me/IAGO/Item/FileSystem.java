package me.IAGO.Item;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.json.JSONException;
import org.json.JSONObject;

import com.alibaba.druid.util.Base64;

import me.IAGO.Log.Log_Intfc;

public class FileSystem implements FileSystem_Intfc {   
    private static Lock _databaselock = new ReentrantLock();
    private static Lock _filelock = new ReentrantLock();
    private Log_Intfc _logger;
    
    public FileSystem(Log_Intfc log) {
        _logger = log;
    }
    
    @Override
    public boolean AddUserInfo(String username, String password) {
        ResultSet re  = null;
        if(GetUserPassword(username) == null) {
            re = DatabaseOperator(
                    Label.CONST_SQLADD.toString(),
                    (pstm) -> {
                        pstm.setString(1, username);
                        pstm.setString(2, password);  
                        return pstm;
                    });
        }
        return re != null;
    }

    @Override
    public boolean RemoveUserInfo(String username) {
        ResultSet re = DatabaseOperator(
                Label.CONST_SQLQUERY.toString(),
                (pstm) -> {
                    pstm.setString(1, username);  
                    return pstm;
                });
        return re != null;
    }

    @Override
    public boolean ChangeUserPassword(String username, String newpassword) {
        ResultSet re = DatabaseOperator(
                Label.CONST_SQLUPGRADE.toString(),
                (pstm) -> {
                    pstm.setString(1, newpassword);
                    pstm.setString(2, username);
                    return pstm;
                });
        return re != null;
    }

    @Override
    public String GetUserPassword(String username) {
        String password = null;
        ResultSet re = DatabaseOperator(
                Label.CONST_SQLUPGRADE.toString(),
                (pstm) -> {
                    pstm.setString(1, username);
                    return pstm;
                });
        if(re != null) {
            try {
                password = re.getString(1);
            } catch (SQLException e) {
                _logger.Error("Database access error");
            } 
        }
        return password;
    }
    
    private Connection GetDatabaseConnection() {
        Connection conn = null;
        try {
            Class.forName(Label.CONST_DATABASEDRIVER.toString());
            // TODO 使用 connection.isValid(1) 减少链接次数
            conn = DriverManager.getConnection(
                    Label.CONST_DATABASEURL.toString(),
                    Label.CONST_DATABASEUSERNAME.toString(), 
                    Label.CONST_DATABASEPASSWORD.toString());
        } catch (ClassNotFoundException e) {
            _logger.Error("Database access error");
        } catch (SQLException e) {
            _logger.Error("SQL statement execution error");
        }
        return conn;
    }
    
    private interface DatabaseParameter {
        public PreparedStatement Filling(PreparedStatement pstm) throws SQLException;
    }
    private ResultSet DatabaseOperator(String sql, DatabaseParameter parameter) {
        ResultSet re = null;
        PreparedStatement pstm = null;
        _databaselock.lock();
        try {
            Connection conn = GetDatabaseConnection();
            if(conn != null) {
                pstm = (PreparedStatement)parameter.Filling(conn.prepareStatement(sql));
                re = pstm.executeQuery();
                pstm.close();
            }
        } catch (SQLException e) {
            _logger.Error("SQL statement execution error");
        }
        _databaselock.unlock();
        return re;
    }

    @Override
    public List<StoreDate_Intfc> GetUserFileIndex(String username) {
        List<StoreDate_Intfc> re = new ArrayList<>();
        _filelock.lock();
        if(CheckUserDir(Label.CONST_FILEMAINDIR + "/" + username)) {
            CatalogTraversal(
                    (Label.CONST_FILEMAINDIR + "/" + username),
                    (File file) -> {
                        try {
                            re.add(new StoreDate(
                                    new JSONObject(
                                            new String(Base64.base64ToByteArray(file.getName())))));
                        } catch (JSONException e) {
                            _logger.Error("An error occurred when system analysis filename, filename perhaps changed");
                        } catch (ParseException e) {
                            _logger.Error("An error occurred when system transcoding filename, filename perhaps changed");
                        };
                    },
                    (File dir) -> {
                        
                    });
        }
        _filelock.unlock();
        return re;
    }

    @Override
    public boolean SaveUserFile(String username, String data, StoreDate_Intfc date) {
        boolean re = false;
        _filelock.lock();
        FileWriter fw;
        if(CheckUserDir(Label.CONST_FILEMAINDIR + "/" + username)) {
            try {
                date.toString();
                fw = new FileWriter(
                        Label.CONST_FILEMAINDIR + 
                        "/" + username +
                        "/" + Base64.byteArrayToBase64(date.toString().getBytes()));
                fw.write(data);
                fw.close();
                re = true;
            } catch (IOException e) {
                _logger.Error("Unable to write to file");
            }
        }
        _filelock.unlock();
        return re;
    }

    @Override
    public boolean DeleteUserFile(String username, StoreDate_Intfc date) {
        _filelock.lock();
        CatalogTraversal(
                (Label.CONST_FILEMAINDIR + "/" + username),
                (File file) -> {
                    if(file.getName().equals(Base64.byteArrayToBase64(date.toString().getBytes()))) {
                        file.delete();   
                    }
                },
                (File dir) -> {
                    dir.delete();
                });
        _filelock.unlock();
        return true;
    }

    @Override
    public String GetUserFile(String username, StoreDate_Intfc date) {
        String re = new String();
        _filelock.lock();
        try {
            re = ReadFileByBytes(
                    Label.CONST_FILEMAINDIR + 
                    "/" + username + 
                    "/" + Base64.byteArrayToBase64(date.toString().getBytes()));
        } catch (FileNotFoundException e) {
            _logger.Error("Unable to find file; An problem with submitted search directory");
        } catch (IOException e) {
            _logger.Error("IO Error");
        }
        _filelock.unlock();
        return re;
    }
    
    private boolean CheckUserDir(String userdir) {
        boolean re = true;
        File file = new File(userdir);
        if(!file.exists()){
            if(!file.mkdirs()) {
                re = false;
                _logger.Error("File directory creation failed");
            }
        }
        return re;
    }
    
    private interface TraversalOperation {
        void Do(File file);
    }
    private void CatalogTraversal(String fileordir, TraversalOperation isfile, TraversalOperation isdir) {
        File file = new File(fileordir);
        if(file.exists()){
            if(file.isFile()){
                isfile.Do(file);
            }
            if(file.isDirectory()){
                File[] files = file.listFiles();
                for (File myfile : files) {
                    CatalogTraversal(fileordir + "/" + myfile.getName(), isfile, isdir);
                }
                if(file.listFiles().length == 0) {
                    isdir.Do(file);
                }
            }
        }
    }

    private String ReadFileByBytes(String filePath)
            throws FileNotFoundException, IOException {
        StringBuffer re = new StringBuffer();
        File file = new File(filePath);
        if(file.exists() && file.isFile()){
            byte[] temp = new byte[1024];
            FileInputStream fileInputStream = new FileInputStream(file);
            int actualread = fileInputStream.read(temp);
            while(actualread != -1){
                byte [] actualbuff;
                if(actualread < 1024) {
                    actualbuff = new byte[actualread];
                    System.arraycopy(temp, 0, actualbuff, 0, actualread);
                }
                else {
                    actualbuff = temp;
                }
                re.append(new String(actualbuff));
                actualread = fileInputStream.read(temp);
            }
            fileInputStream.close();
        }
        else {
            _logger.Error("Unable to find file");
        }
        return re.toString();
    }
}
