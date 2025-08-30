import React, { useState, useRef } from 'react';
import './ProvenanceTestUI.css';

const ProvenanceTestUI = () => {
  const [provenance, setProvenance] = useState(null);
  const [operations, setOperations] = useState([]);
  const [jsonOutput, setJsonOutput] = useState('');
  const fileInputRef = useRef();

  const initializeProvenance = () => {
    const newProvenance = {
      assetId: Math.random().toString(36).substr(2, 9),
      createdAt: new Date().toISOString(),
      meta: {
        width: 1920,
        height: 1080,
        durationMs: 60000,
        format: 'mp4',
        frameRate: 30
      },
      provenance: {
        c2paPresent: true,
        signed: true,
        actionsCount: 0
      },
      ops: [],
      export: null
    };
    setProvenance(newProvenance);
    setOperations([]);
    updateJsonOutput(newProvenance);
  };

  const addOperation = (type, params) => {
    const operation = {
      type,
      timestamp: new Date().toISOString(),
      parameters: params
    };

    const updatedOps = [...operations, operation];
    const updatedProvenance = {
      ...provenance,
      ops: updatedOps,
      provenance: {
        ...provenance.provenance,
        actionsCount: updatedOps.length
      }
    };

    setOperations(updatedOps);
    setProvenance(updatedProvenance);
    updateJsonOutput(updatedProvenance);
  };

  const updateJsonOutput = (prov) => {
    setJsonOutput(JSON.stringify(prov, null, 2));
  };

  const undo = () => {
    if (operations.length > 0) {
      const updatedOps = operations.slice(0, -1);
      const updatedProvenance = {
        ...provenance,
        ops: updatedOps,
        provenance: {
          ...provenance.provenance,
          actionsCount: updatedOps.length
        }
      };
      setOperations(updatedOps);
      setProvenance(updatedProvenance);
      updateJsonOutput(updatedProvenance);
    }
  };

  const saveToFile = () => {
    const blob = new Blob([jsonOutput], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `provenance-${provenance.assetId}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const loadFromFile = (event) => {
    const file = event.target.files[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (e) => {
        try {
          const loaded = JSON.parse(e.target.result);
          setProvenance(loaded);
          setOperations(loaded.ops || []);
          setJsonOutput(JSON.stringify(loaded, null, 2));
        } catch (error) {
          alert('Invalid JSON file');
        }
      };
      reader.readAsText(file);
    }
  };

  return (
    <div className="provenance-test-ui">
      <h1>Media Provenance Test UI</h1>

      <div className="controls">
        <button onClick={initializeProvenance} disabled={provenance}>
          Initialize Provenance
        </button>

        {provenance && (
          <>
            <div className="operation-buttons">
              <h3>Add Operations:</h3>
              <button onClick={() => addOperation('transform', { crop: [100, 100, 800, 600] })}>
                Crop
              </button>
              <button onClick={() => addOperation('transform', { rotate: 90 })}>
                Rotate
              </button>
              <button onClick={() => addOperation('transform', { trim: [1000, 5000] })}>
                Trim
              </button>
              <button onClick={() => addOperation('adjust', { brightness: 15 })}>
                Brightness
              </button>
              <button onClick={() => addOperation('adjust', { contrast: 10 })}>
                Contrast
              </button>
              <button onClick={() => addOperation('filter', { lut: 'warm', intensity: 0.8 })}>
                LUT Filter
              </button>
              <button onClick={() => addOperation('privacy_blur', {
                method: 'gaussian',
                areas: [{ x: 100, y: 100, width: 200, height: 200, area_pct: 2.1 }],
                radius: 15
              })}>
                Gaussian Blur
              </button>
            </div>

            <div className="management-buttons">
              <button onClick={undo} disabled={operations.length === 0}>
                Undo
              </button>
              <button onClick={saveToFile}>
                Save to File
              </button>
              <input
                ref={fileInputRef}
                type="file"
                accept=".json"
                onChange={loadFromFile}
                style={{ display: 'none' }}
              />
              <button onClick={() => fileInputRef.current?.click()}>
                Load from File
              </button>
            </div>

            <div className="stats">
              <h3>Current State:</h3>
              <p>Asset ID: {provenance.assetId}</p>
              <p>Operations: {operations.length}</p>
              <p>Dimensions: {provenance.meta.width}x{provenance.meta.height}</p>
            </div>
          </>
        )}
      </div>

      {jsonOutput && (
        <div className="json-output">
          <h3>JSON Output:</h3>
          <pre>{jsonOutput}</pre>
        </div>
      )}
    </div>
  );
};

export default ProvenanceTestUI;