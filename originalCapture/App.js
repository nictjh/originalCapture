import { StatusBar } from 'expo-status-bar';
import React from 'react';
import { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View, Platform, ScrollView, Button, Alert } from 'react-native';
import { openCamera } from './native/CameraAttest';
import EditorScreen from './EditorScreen'; 

export default function App() {
  const [result, setResult] = useState(null);
  const [showEditor, setShowEditor] = useState(false);
  const [currentMedia, setCurrentMedia] = useState(null);
  const launchedRef = useRef(false);

  const handleSave = async (editedUri) => {
    try {
      // Just update the state with the edited image URI
      setShowEditor(false);
      setCurrentMedia(null);
      
      // Update the result to show success
      setResult({ 
        action: 'save', 
        ok: true, 
        mediaPath: editedUri, 
        message: 'Image edited successfully!'
      });
      
    } catch (error) {
      console.error('Error processing image:', error);
      Alert.alert('Error', 'Failed to process image. Please try again.');
      setResult({ 
        action: 'error', 
        ok: false, 
        message: error.message 
      });
    }
  };

  const launchCamera = async () => {
    try {
      const res = await openCamera();
      setResult(res);
      
      if (res.action === 'edit' && res.mediaPath) {
        setCurrentMedia(res.mediaPath);
        setShowEditor(true);
      } else {
        // If the camera action wasn't 'edit' or didn't return media, hide editor
        setShowEditor(false);
        setCurrentMedia(null);
      }
    } catch (e) {
      setResult({ action: 'error', ok: false, message: String(e) });
      setShowEditor(false);
      setCurrentMedia(null);
    }
  };


  useEffect(() => {
    // Avoid double-launch on fast refresh / strict mode
    if (launchedRef.current) return;
    launchedRef.current = true;

    (async () => {
      try {
        // Immediately open native camera on startup
        const res = await openCamera();
        setResult(res);

        if (res.action === 'edit' && res.mediaPath) {
          setCurrentMedia(res.mediaPath);
          setShowEditor(true);
        } else {
          // If the camera action wasn't 'edit' or didn't return media, hide editor
          setShowEditor(false);
          setCurrentMedia(null);
        }
      } catch (e) {
        setResult({ action: 'error', ok: false, message: String(e) });
        setShowEditor(false);
        setCurrentMedia(null);
      }
    })();
  }, []);

  // Conditionally render the EditorScreen if there's media to edit
  if (showEditor && currentMedia) {
    return (
      <EditorScreen
        mediaUri={currentMedia}
        onSave={handleSave}
        onCancel={() => {
          console.log('Editing cancelled.');
          setShowEditor(false);
          setCurrentMedia(null); // Clear the media on cancel
          setResult({ action: 'cancel', ok: true, message: 'Editing cancelled.' });
        }}
      />
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>
        {Platform.OS === 'android'
          ? 'Launching native camera…'
          : 'Android only: native camera module'}
      </Text>

      <ScrollView style={styles.result} contentContainerStyle={{ padding: 12 }}>
        <Text style={styles.heading}>Last Result</Text>
        <Text selectable>{JSON.stringify(result, null, 2)}</Text>
        <Button title="Open Camera" onPress={launchCamera} style={styles.button} />
      </ScrollView>

      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#fff', paddingTop: 64, paddingHorizontal: 20 },
  title: { fontSize: 18, fontWeight: '600', marginBottom: 16, textAlign: 'center' },
  heading: { fontSize: 16, fontWeight: '500', marginBottom: 8 },
  result: { marginTop: 16, width: '100%' },
  button: {
    marginTop: 20,
  }
});