import { useEffect, useRef, useState } from 'react';
import { orderStatusLabel } from '../../utils/orderStatusLabel.js';

export default function OrderStatusSelect({ id, value, options, disabled, onChange }) {
  const rootRef = useRef(null);
  const [expanded, setExpanded] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const open = expanded && !disabled;

  useEffect(() => {
    if (!open) return undefined;
    function closeOutside(event) {
      if (!rootRef.current?.contains(event.target)) setExpanded(false);
    }
    document.addEventListener('pointerdown', closeOutside);
    return () => document.removeEventListener('pointerdown', closeOutside);
  }, [open]);

  function showOptions(index = Math.max(0, options.indexOf(value))) {
    setActiveIndex(index);
    setExpanded(true);
  }

  function choose(index) {
    if (!options[index]) return;
    onChange(options[index]);
    setExpanded(false);
  }

  function handleKeyDown(event) {
    if (event.key === 'Tab') {
      setExpanded(false);
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      setExpanded(false);
      return;
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' '].includes(event.key)) return;
    event.preventDefault();
    if (event.key === 'Enter' || event.key === ' ') {
      if (open) choose(activeIndex);
      else showOptions();
    } else if (event.key === 'Home' || event.key === 'End') {
      showOptions(event.key === 'Home' ? 0 : options.length - 1);
    } else if (!open) {
      showOptions(event.key === 'ArrowUp' ? options.length - 1 : 0);
    } else {
      setActiveIndex(index => (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length);
    }
  }

  return (
    <div className="order-status-select" ref={rootRef}
      onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setExpanded(false); }}>
      <button id={id} className="order-status-select-trigger" type="button" role="combobox"
        aria-labelledby={`${id}-label`} aria-controls={`${id}-options`} aria-haspopup="listbox"
        aria-expanded={open} aria-activedescendant={open ? `${id}-option-${activeIndex}` : undefined}
        disabled={disabled} onKeyDown={handleKeyDown}
        onClick={() => { if (open) setExpanded(false); else showOptions(); }}>
        <span>{value ? orderStatusLabel(value) : 'Selecione o status'}</span>
        <span className="order-status-select-arrow" aria-hidden="true" />
      </button>
      {open && (
        <ul id={`${id}-options`} className="order-status-options" role="listbox" aria-labelledby={`${id}-label`}>
          {options.map((status, index) => (
            <li id={`${id}-option-${index}`} key={status} role="option" aria-selected={status === value}
              className={index === activeIndex ? 'order-status-option-active' : undefined}
              onPointerMove={() => setActiveIndex(index)} onPointerDown={event => event.preventDefault()}
              onClick={() => choose(index)}>{orderStatusLabel(status)}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
