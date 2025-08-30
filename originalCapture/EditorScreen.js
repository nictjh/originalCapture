// EditorScreen.js
import React, { useState, useEffect, useRef } from 'react';
import { 
  View, 
  TouchableOpacity, 
  StyleSheet, 
  Text, 
  ActivityIndicator, 
  Alert, 
  Platform,
  ScrollView,
  TextInput, 
  Dimensions 
} from 'react-native';
import * as ImageManipulator from 'expo-image-manipulator';
import { Ionicons } from '@expo/vector-icons';
import Slider from '@react-native-community/slider';

import { 
  Canvas, 
  Image, 
  useImage, 
  ColorMatrix, 
  Blur,
  Text as SkiaText, 
  useFont, 
} from '@shopify/react-native-skia';
import * as FileSystem from 'expo-file-system';

const { width: SCREEN_WIDTH, height: SCREEN_HEIGHT } = Dimensions.get('window');

const ensureFileUri = (uri) => {
    if (!uri) return null;
    if (uri.startsWith('file://') || uri.startsWith('content://') || uri.startsWith('http://') || uri.startsWith('https://')) {
        return uri;
    }
    return `file://${uri}`;
};

const createColorMatrix = ({ brightness, contrast, saturation, isGrayscale }) => {
  let finalMatrix = [
    1, 0, 0, 0, 0,
    0, 1, 0, 0, 0,
    0, 0, 1, 0, 0,
    0, 0, 0, 1, 0,
  ];

  // Apply Grayscale FIRST if active
  if (isGrayscale) {
    const lumR = 0.299;
    const lumG = 0.587;
    const lumB = 0.114;
    finalMatrix = [
      lumR, lumG, lumB, 0, 0,
      lumR, lumG, lumB, 0, 0,
      lumR, lumG, lumB, 0, 0,
      0,    0,    0,    1, 0,
    ];
  }

  // Apply Saturation (if not already grayscale, or to the grayscale output)
  if (saturation !== 0) {
    const s = 1 + (saturation / 100);
    const lumR = 0.3086; 
    const lumG = 0.6094;
    const lumB = 0.0820;

    const sr = (1 - s) * lumR;
    const sg = (1 - s) * lumG;
    const sb = (1 - s) * lumB;

    const saturationMatrix = [
      s + sr, sg, sb, 0, 0,
      sr, s + sg, sb, 0, 0,
      sr, sg, s + sb, 0, 0,
      0, 0, 0, 1, 0,
    ];
    
    // Matrix multiplication: finalMatrix = saturationMatrix * finalMatrix
    const currentMatrixCopy = [...finalMatrix];
    const newMatrix = Array(20).fill(0);

    for (let i = 0; i < 4; i++) { // Rows of the new matrix
      for (let j = 0; j < 5; j++) { // Columns of the new matrix
        for (let k = 0; k < 4; k++) { // For the 4x4 part
          newMatrix[i * 5 + j] += saturationMatrix[i * 5 + k] * currentMatrixCopy[k * 5 + j];
        }
        // Add the offset part of the saturation matrix if it's the 5th column
        if (j === 4) {
          newMatrix[i * 5 + j] += saturationMatrix[i * 5 + 4];
        }
      }
    }
    finalMatrix = newMatrix;
  }

  // Apply Contrast
  if (contrast !== 0) {
    const c = 1 + (contrast / 100);
    const offset = 0.5 * (1 - c);
    for (let i = 0; i < 3; i++) { 
      finalMatrix[i * 5 + i] *= c; 
      finalMatrix[i * 5 + 4] += offset; 
    }
  }

  // Apply Brightness LAST (as simple offset)
  if (brightness !== 0) {
    const b = brightness / 100;
    finalMatrix[4] += b; 
    finalMatrix[9] += b; 
    finalMatrix[14] += b; 
  }

  return finalMatrix;
};


function EditorScreen({ mediaUri, onSave, onCancel }) {
    const [currentUri, setCurrentUri] = useState(null);
    const [isLoading, setIsLoading] = useState(false);
    const [hasEdits, setHasEdits] = useState(false);
    const [activeTool, setActiveTool] = useState(null); 
    const [adjustments, setAdjustments] = useState({
        brightness: 0,
        contrast: 0,
        saturation: 0,
        blur: 0,
        isGrayscale: false,
    });
    const [textOverlay, setTextOverlay] = useState({
        text: '',
        color: '#FFFFFF',
        fontSize: 30,
        x: SCREEN_WIDTH / 2, 
        y: SCREEN_HEIGHT / 2, 
    });

    const skiaRef = useRef(null);
    const skiaImage = useImage(currentUri); 
    const font = useFont(require('./assets/Roboto-Regular.ttf'), textOverlay.fontSize); // Ensure you have a font file, e.g., in ./assets/

    useEffect(() => {
        if (mediaUri) {
            const formattedUri = ensureFileUri(mediaUri);
            setCurrentUri(formattedUri);
        }
    }, [mediaUri]);

    // --- applyDestructiveManipulation is for edits that change the base image file ---
    const applyDestructiveManipulation = async (actions) => {
        if (isLoading || !currentUri) return;
        setIsLoading(true);
        try {
            const manipResult = await ImageManipulator.manipulateAsync(
                currentUri,
                actions,
                { compress: 0.88, format: ImageManipulator.SaveFormat.JPEG }
            );
            if (manipResult && manipResult.uri) {
                const newUri = ensureFileUri(manipResult.uri);
                setCurrentUri(newUri); 
                setHasEdits(true);
            }
        } catch (error) {
            console.error('Error applying destructive manipulation:', error);
            Alert.alert('Error', `Could not apply the edit: ${error.message || error}`);
        } finally {
            setIsLoading(false);
        }
    };

    // --- Handlers for destructive edits (Crop, Rotate) ---
    const handleRotate = () => applyDestructiveManipulation([{ rotate: 90 }]);
    const handleCrop = () => applyDestructiveManipulation([{ crop: { originX: 20, originY: 30, width: 800, height: 1000 } }]);

    // --- Handler for B&W toggle (Skia-based) ---
    const handleGrayscaleToggle = () => {
      setAdjustments(a => ({ ...a, isGrayscale: !a.isGrayscale }));
      setHasEdits(true); 
    };

    // --- Handler for saving the final image from the Skia Canvas ---
    const handleSave = async () => {
        if (!skiaRef.current) {
            Alert.alert('Error', 'Cannot save image, canvas is not ready.');
            return;
        }
        setIsLoading(true);
        try {
            const snapshot = await skiaRef.current.makeImageSnapshot();
            if (snapshot) {
                const base64 = snapshot.encodeToBase64();
                const newFilePath = `${FileSystem.cacheDirectory}edited-${Date.now()}.jpg`;
                await FileSystem.writeAsStringAsync(newFilePath, base64, {
                    encoding: FileSystem.EncodingType.Base64,
                });
                console.log('Image saved to:', newFilePath);
                onSave(newFilePath);
            }
        } catch (error) {
            console.error('Error saving image:', error);
            Alert.alert('Error', 'Failed to save the edited image.');
        } finally {
            setIsLoading(false);
        }
    };

    const handleReset = () => {
        const formattedMediaUri = ensureFileUri(mediaUri);
        setCurrentUri(formattedMediaUri);
        setHasEdits(false);
        setAdjustments({ brightness: 0, contrast: 0, saturation: 0, blur: 0, isGrayscale: false });
        setTextOverlay({ text: '', color: '#FFFFFF', fontSize: 30, x: SCREEN_WIDTH / 2, y: SCREEN_HEIGHT / 2 });
        setActiveTool(null);
    };

    const renderAdjustmentSliders = () => {
        if (activeTool !== 'adjust') return null;
        return (
            <View style={styles.adjustmentPanel}>
                <Text style={styles.sliderLabel}>Brightness: {Math.round(adjustments.brightness)}</Text>
                <Slider style={styles.slider} minimumValue={-100} maximumValue={100} step={1} value={adjustments.brightness} onValueChange={(v) => { setAdjustments(a => ({ ...a, brightness: v })); setHasEdits(true); }} />
                <Text style={styles.sliderLabel}>Contrast: {Math.round(adjustments.contrast)}</Text>
                <Slider style={styles.slider} minimumValue={-100} maximumValue={100} step={1} value={adjustments.contrast} onValueChange={(v) => { setAdjustments(a => ({ ...a, contrast: v })); setHasEdits(true); }} />
                <Text style={styles.sliderLabel}>Saturation: {Math.round(adjustments.saturation)}</Text>
                <Slider style={styles.slider} minimumValue={-100} maximumValue={100} step={1} value={adjustments.saturation} onValueChange={(v) => { setAdjustments(a => ({ ...a, saturation: v })); setHasEdits(true); }} />
                <Text style={styles.sliderLabel}>Gaussian Blur: {Math.round(adjustments.blur)}</Text>
                <Slider style={styles.slider} minimumValue={0} maximumValue={20} step={1} value={adjustments.blur} onValueChange={(v) => { setAdjustments(a => ({ ...a, blur: v })); setHasEdits(true); }} />
            </View>
        );
    };

    const renderTextOverlayControls = () => {
        if (activeTool !== 'text') return null;
        return (
            <View style={styles.adjustmentPanel}>
                <TextInput
                    style={styles.textInput}
                    placeholder="Enter text here"
                    placeholderTextColor="#999"
                    value={textOverlay.text}
                    onChangeText={(t) => { setTextOverlay(to => ({ ...to, text: t })); setHasEdits(true); }}
                />
                <Text style={styles.sliderLabel}>Font Size: {Math.round(textOverlay.fontSize)}</Text>
                <Slider style={styles.slider} minimumValue={10} maximumValue={100} step={1} value={textOverlay.fontSize} onValueChange={(v) => { setTextOverlay(to => ({ ...to, fontSize: v })); setHasEdits(true); }} />
                
                <Text style={styles.sliderLabel}>Text Color:</Text>
                <View style={styles.colorPalette}>
                    {['#FFFFFF', '#000000', '#FF0000', '#00FF00', '#0000FF', '#FFFF00'].map(color => (
                        <TouchableOpacity
                            key={color}
                            style={[styles.colorOption, { backgroundColor: color, borderWidth: textOverlay.color === color ? 2 : 0 }]}
                            onPress={() => { setTextOverlay(to => ({ ...to, color: color })); setHasEdits(true); }}
                        />
                    ))}
                </View>
                {/* Note: Dragging text position on canvas would require gesture handlers, which is more complex */}
            </View>
        );
    };

    return (
        <View style={styles.container}>
            <View style={styles.topBar}>
                <TouchableOpacity style={styles.topButton} onPress={onCancel} disabled={isLoading}><Ionicons name="close" size={30} color="#fff" /></TouchableOpacity>
                <TouchableOpacity style={styles.topButton} onPress={handleSave} disabled={isLoading || !skiaImage}><Ionicons name="checkmark-done" size={30} color="#4CAF50" /></TouchableOpacity>
            </View>

            <View style={styles.imageContainer}>
                {skiaImage ? (
                    <Canvas style={{ flex: 1, width: '100%' }} ref={skiaRef}>
                        <Image image={skiaImage} x={0} y={0} width={skiaImage.width()} height={skiaImage.height()} fit="contain">
                            <ColorMatrix matrix={createColorMatrix(adjustments)} />
                            {adjustments.blur > 0 && <Blur blur={adjustments.blur} />}
                        </Image>
                        {/* Render Skia Text if textOverlay.text is not empty and font is loaded */}
                        {textOverlay.text !== '' && font && (
                            <SkiaText 
                                text={textOverlay.text} 
                                x={textOverlay.x - font.getTextWidth(textOverlay.text) / 2} // Center text horizontally
                                y={textOverlay.y + textOverlay.fontSize / 2} // Center text vertically
                                font={font} 
                                color={textOverlay.color} 
                            />
                        )}
                    </Canvas>
                ) : (
                    <ActivityIndicator size="large" color="#fff" />
                )}
                {isLoading && <View style={styles.loadingOverlay}><ActivityIndicator size="large" color="#fff" /></View>}
            </View>

            {renderAdjustmentSliders()}
            {renderTextOverlayControls()} {/* Render text controls */}

            <View style={styles.bottomToolbar}>
                <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.toolScrollContainer}>
                    <TouchableOpacity style={styles.toolButton} onPress={handleReset} disabled={isLoading}><Ionicons name="arrow-undo" size={24} color="#fff" /><Text style={styles.toolText}>Reset</Text></TouchableOpacity>
                    
                    {/* B&W Filter Toggle */}
                    <TouchableOpacity style={[styles.toolButton, adjustments.isGrayscale && styles.activeToolButton]} onPress={handleGrayscaleToggle} disabled={isLoading}><Ionicons name="color-fill" size={24} color="#fff" /><Text style={styles.toolText}>B&W</Text></TouchableOpacity>
                    
                    <TouchableOpacity style={styles.toolButton} onPress={handleRotate} disabled={isLoading}><Ionicons name="refresh" size={24} color="#fff" /><Text style={styles.toolText}>Rotate</Text></TouchableOpacity>
                    <TouchableOpacity style={styles.toolButton} onPress={handleCrop} disabled={isLoading}><Ionicons name="crop" size={24} color="#fff" /><Text style={styles.toolText}>Crop</Text></TouchableOpacity>
                    
                    <TouchableOpacity style={[styles.toolButton, activeTool === 'adjust' && styles.activeToolButton]} onPress={() => setActiveTool(a => a === 'adjust' ? null : 'adjust')} disabled={isLoading}><Ionicons name="color-filter" size={24} color="#fff" /><Text style={styles.toolText}>Adjust</Text></TouchableOpacity>
                    
                    {/* Text Overlay Tool */}
                    <TouchableOpacity style={[styles.toolButton, activeTool === 'text' && styles.activeToolButton]} onPress={() => setActiveTool(a => a === 'text' ? null : 'text')} disabled={isLoading}><Ionicons name="text" size={24} color="#fff" /><Text style={styles.toolText}>Text</Text></TouchableOpacity>
                
                    </ScrollView>
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    container: { flex: 1, backgroundColor: '#000' },
    topBar: { position: 'absolute', top: 40, left: 0, right: 0, flexDirection: 'row', justifyContent: 'space-between', paddingHorizontal: 20, zIndex: 10 },
    topButton: { padding: 8, borderRadius: 20, backgroundColor: 'rgba(0,0,0,0.4)' },
    imageContainer: { flex: 1, justifyContent: 'center', alignItems: 'center' },
    loadingOverlay: { ...StyleSheet.absoluteFillObject, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'center', alignItems: 'center' },
    adjustmentPanel: { position: 'absolute', bottom: 100, left: 0, right: 0, backgroundColor: 'rgba(26, 26, 26, 0.9)', padding: 15, borderTopWidth: 1, borderTopColor: '#333', zIndex: 99 }, // Increased zIndex
    slider: { width: '100%', height: 40 },
    sliderLabel: { color: '#fff', marginBottom: 5, marginTop: 10 },
    bottomToolbar: { position: 'absolute', bottom: 0, left: 0, right: 0, height: 100, backgroundColor: 'rgba(26, 26, 26, 0.9)', paddingHorizontal: 10, paddingBottom: Platform.OS === 'ios' ? 20 : 0, zIndex: 100 }, // Ensure toolbar is on top
    toolScrollContainer: { alignItems: 'center', paddingRight: 20 },
    toolButton: { alignItems: 'center', justifyContent: 'center', paddingHorizontal: 15, height: '100%' },
    activeToolButton: { backgroundColor: 'rgba(76, 175, 80, 0.2)', borderRadius: 8 },
    toolText: { color: '#fff', fontSize: 12, marginTop: 6 },
    // Text overlay specific styles
    textInput: {
        width: '100%',
        height: 50,
        backgroundColor: 'rgba(255,255,255,0.1)',
        color: '#fff',
        borderRadius: 8,
        paddingHorizontal: 15,
        marginBottom: 10,
        fontSize: 16,
    },
    colorPalette: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        justifyContent: 'center',
        marginTop: 10,
    },
    colorOption: {
        width: 30,
        height: 30,
        borderRadius: 15,
        margin: 5,
        borderColor: '#007AFF',
    },
});

export default EditorScreen;