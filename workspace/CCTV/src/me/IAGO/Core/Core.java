package me.IAGO.Core;

import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

import me.IAGO.Item.FileSystem_Intfc;
import me.IAGO.Item.Label;
import me.IAGO.Log.Log_Intfc;
import me.IAGO.Media.Media;
import me.IAGO.Media.Media_Intfc;
import me.IAGO.Security.Security;
import me.IAGO.Security.Security_Intfc.PrivilegeLevel;

public class Core implements Core_Intfc {
    private class PortInfo {
        public Security _security;
        public Media_Intfc.MediaDataWatcher _mediawatcher;
        PortInfo(FileSystem_Intfc filesystem, Media_Intfc.MediaDataWatcher sendfunction) {
            _security = new Security(filesystem);
            _mediawatcher = sendfunction;
        }
    }
    private Log_Intfc _logger;
    private static FileSystem_Intfc _filesystem = null;
    private Media _media;
    private Map<String, PortInfo> _userlinkport;
    
    public Core(String ownername, FileSystem_Intfc filesystem, Log_Intfc log){
        _filesystem = filesystem;
        _media = new Media(_filesystem, ownername);
        _logger = log;
        _userlinkport = new HashMap<>();  
    }
    
    @Override
    public void OnOpen(String portid, Media_Intfc.MediaDataWatcher sendfunction) {
        synchronized(this) {
            _userlinkport.put(portid, new PortInfo(_filesystem, sendfunction));
        }
    }
    
    @Override
    public JSONObject OnMessage(String portid, JSONObject message) {
        JSONObject backto = new JSONObject();
        synchronized(this) {
            PortInfo portinfo = _userlinkport.get(portid);
            if(portinfo != null) {
                try {
                    switch(Label.fromString(message.getString(Label.SERVER.toString()))) {
                    case SERVER_LOGON : {
                        backto.put(
                                Label.FIELD_PUBLICKEY.toString(), 
                                GetPublicKey());
                        break;
                    }
                    case SERVER_LOGONCONFIRM : {
                        JSONObject logonmessage = new JSONObject(PrivatekeyDecryption(message.getString(Label.FIELD_MESSAGE.toString())));
                        try {
                            LogonInfoCheck(logonmessage);
                            backto.put(
                                    Label.FIELD_MESSAGE.toString(), 
                                    _filesystem.AddUserInfo(
                                            logonmessage.getString(Label.FIELD_USERNAME.toString()), 
                                            logonmessage.getString(Label.FIELD_USERPASSWORD.toString())));
                        } catch (JSONException e) {
                            // TODO 格式错误
                        } catch(Exception e) {
                            // TODO 用户名或者密码检查错误
                        } 
                        break;
                    }
                    case SERVER_LOGIN : {
                        String loginusername = message.getString(Label.FIELD_USERNAME.toString());
                        boolean useralreadlogin = false;
                        for (Map.Entry<String, PortInfo> otherport : _userlinkport.entrySet()) {
                            if(otherport.getValue()._security.VerificationStatus()) {
                                if(otherport.getValue()._security.GetUsername().equals(loginusername)) {
                                    useralreadlogin = true;
                                    break;
                                }
                            }
                        }
                        if(!useralreadlogin) {
                            backto.put(
                                    Label.FIELD_ONETIMECODE.toString(), 
                                    portinfo._security.OnetimeVerificationInfo(loginusername, 300));
                            backto.put(Label.FIELD_MESSAGE.toString(), true);
                        }
                        else {
                            backto.put(Label.FIELD_ONETIMECODE.toString(), "");
                            backto.put(Label.FIELD_MESSAGE.toString(), false);
                        }
                        break;
                    }
                    case SERVER_VERIFICATION : {
                        backto.put(
                                Label.FIELD_VERIFICATIONSTATUS.toString(), 
                                portinfo._security.Verification(message.getString(Label.FIELD_MESSAGE.toString())));     
                        break;
                    }
                    case SERVER_VERIFICATION_STATUS : {
                        backto.put(
                                Label.FIELD_VERIFICATIONSTATUS.toString(), 
                                portinfo._security.VerificationStatus());
                        break;
                    }
                    case SERVER_GETMEDIAINDEX : {
                        if(portinfo._security.VerificationStatus()) {
                            backto.put(
                                    Label.FIELD_MEDIAINDEX.toString(), 
                                    _media.GetMediaRecordDate());
                            backto.put(Label.FIELD_MESSAGE.toString(), true);
                        }
                        else {
                            backto.put(Label.FIELD_MESSAGE.toString(), false);
                        }
                        break;
                    }
                    case SERVER_DELETEMEDIA : {
                        if(portinfo._security.Privilege(_media.OwnerName()) == PrivilegeLevel.Owner) {
                            backto.put(
                                    Label.FIELD_MESSAGE.toString(), 
                                    _media.DelectMediaDate(new JSONObject(message.getString(Label.FIELD_MEDIAINDEX.toString()))));
                        }
                        else {
                            backto.put(Label.FIELD_MESSAGE.toString(), false);
                        }
                        break;
                    }
                    case SERVER_PUSHMEDIA: {
                        if(portinfo._security.Privilege(_media.OwnerName()) == PrivilegeLevel.Owner) {
                            String mediadata = message.getString(Label.FIELD_MEDIADATA.toString());
                            _logger.Info(String.valueOf(mediadata.length()));
                            _media.PushMediaData(mediadata);
                            backto.put(Label.FIELD_MESSAGE.toString(), true);
                        }
                        else {
                            backto.put(Label.FIELD_MESSAGE.toString(), false);
                        }
                        break;
                    }
                    case SERVER_STARTWATCH : {
                        //  TODO 权限检查 
                        if(portinfo._security.VerificationStatus()) {
                            backto.put(
                                    Label.FIELD_MESSAGE.toString(),
                                    _media.StartMediaForward(
                                            portid, 
                                            (String data) -> {
                                                JSONObject pack = new JSONObject();
                                                pack.put(
                                                        Label.FIELD_MEDIADATA.toString(), 
                                                        data);
                                                portinfo._mediawatcher.Push(pack.toString());
                                                return true;
                                            }));
                        }
                        else {
                            backto.put(Label.FIELD_MESSAGE.toString(), false);
                        }
                        break;
                    }
                    case SERVER_STOPWATCH : {
                        if(portinfo._security.VerificationStatus()) {
                            backto.put(
                                    Label.FIELD_MESSAGE.toString(), 
                                    _media.StopMediaForward(portid));
                        }
                        else {
                            backto.put(Label.FIELD_MESSAGE.toString(), false);
                        }
                        break;
                    }
                    case SERVER_PULLMEDIA : {
                        if(portinfo._security.VerificationStatus()) {
                            backto.put(
                                    Label.FIELD_MEDIADOWNLOAD.toString(), 
                                    _media.PullMediaData(new JSONObject(message.getString(Label.FIELD_MEDIAINDEX.toString()))));
                        }
                        else {
                            backto.put(Label.FIELD_MEDIADOWNLOAD.toString(), "");
                        }
                        break;
                    }
                    case SERVER_CONFIGMEDIA : {
                        if(portinfo._security.Privilege(_media.OwnerName()) == PrivilegeLevel.Owner) {
                            backto.put(
                                    Label.FIELD_MESSAGE.toString(), 
                                    _media.Config(new JSONObject(message.getString(Label.FIELD_CONFIGMEDIA.toString()))));
                        }
                        else {
                            backto.put(Label.FIELD_MESSAGE.toString(), false);
                        }
                        break;
                    }

                    default:
                        backto.put(Label.FIELD_MESSAGE.toString(), "Unable to process service request");
                        _logger.Error("Unable to process service request");
                        break;
                    }
                }
                catch (ParseException w) {
                    _logger.Error("An unrecoverable error occurred during the JSON conversion process");
                }
                catch (JSONException e) {
                    _logger.Error("An error occurred during the acquisition JSON key process");
                }
                catch (IllegalArgumentException e) {
                    _logger.Error("Illegal parameter");
                }
            }
            else {
                _logger.Error("Unregistered port trying to access");
            }
        }
        return backto;
    }
    
    @Override
    public void OnClose(String portid) {
        synchronized(this) {
            _userlinkport.remove(portid);
        }
    }
    
    @Override
    public void OnError(String portid, Throwable error) {
        synchronized(this) {
            _logger.Error("Websocket get error" + error);
        }
    }
    
    private String GetPublicKey() {
        return "";
    }
    private String PrivatekeyDecryption(String data) {
        return data;
    }
    private void LogonInfoCheck(JSONObject logoninfo) 
            throws Exception, JSONException {
    }
}
