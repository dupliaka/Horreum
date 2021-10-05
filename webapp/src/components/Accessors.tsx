import React, { useState, useEffect, useCallback } from "react"
import { useDispatch } from "react-redux"

import { addOrUpdateExtractor, listExtractors } from "../domain/schemas/api"
import SchemaSelect from "./SchemaSelect"

import { alertAction } from "../alerts"
import { testJsonPath } from "../domain/schemas/api"

import {
    ActionGroup,
    Button,
    Form,
    FormGroup,
    Radio,
    Select,
    SelectOption,
    TextInput,
    Modal,
} from "@patternfly/react-core"

import { AddCircleOIcon } from "@patternfly/react-icons"

function distinctSorted(list: Extractor[], selector: (e: Extractor) => any): Extractor[] {
    return Array.from(new Set(list.map(selector)))
        .map(a => list.find(o => selector(o) === a) || a) // distinct
        .sort((a, b) => selector(a).localeCompare(selector(b)))
}

function baseName(name: string) {
    return name.endsWith("[]") ? name.substring(0, name.length - 2) : name
}

export type ValidationResult = {
    valid: boolean
    reason: string
    errorCode: number
    sqlState: string
}

export interface Extractor {
    accessor: string
    schema?: string
    jsonpath?: string
    // upload-only fields
    newName?: string
    deleted?: boolean
    changed?: boolean
    // temprary fields
    validationTimer?: any
    validationResult?: ValidationResult
}

type AccessorsProps = {
    value: string[]
    onChange(selectors: string[]): void
    isReadOnly: boolean
    allowArray?: boolean
    error?: string
}

export default function Accessors({
    value = [],
    onChange = (_: string[]) => {
        /* noop */
    },
    isReadOnly,
    allowArray,
    error,
}: AccessorsProps) {
    const [created, setCreated] = useState<Extractor>({ accessor: "" })
    const onCreate = (newValue: string) => {
        setCreated({ accessor: newValue })
        setDisabledSchemas([])
        setCreateOpen(true)
    }
    const [disabledSchemas, setDisabledSchemas] = useState<string[]>([])
    const [isExpanded, setExpanded] = useState(false)
    const [options, setOptions] = useState(value.map(v => ({ accessor: v })))
    const [selected, setSelected] = useState(value)
    const onSchemaChange = useCallback(value => {
        setCreated(c => ({ ...c, schema: value }))
    }, [])
    useEffect(() => {
        listExtractors().then((response: Extractor[]) => {
            setOptions(response)
        })
    }, [])
    useEffect(() => setSelected(value), [value])
    const [createOpen, setCreateOpen] = useState(false)

    const [variantOpen, setVariantOpen] = useState(false)
    const [variant, setVariant] = useState(0)
    const [addedOption, setAddedOption] = useState<Extractor | undefined>(undefined)
    const openVariantModal = (newOption: Extractor) => {
        setAddedOption(newOption)
        setVariant(newOption.accessor.endsWith("[]") ? 1 : 0)
        setVariantOpen(true)
    }
    const dispatch = useDispatch()

    return (
        <>
            <Select
                variant="typeaheadmulti"
                aria-label="Select accessor"
                validated={error ? "error" : "default"}
                placeholderText="Select accessor"
                isCreatable={true}
                onCreateOption={onCreate}
                isOpen={isExpanded}
                onToggle={setExpanded}
                selections={selected}
                isDisabled={isReadOnly}
                onClear={() => {
                    setSelected([])
                    setExpanded(false)
                    onChange([])
                }}
                onSelect={(e, newValue) => {
                    const newSelected = newValue.toString()
                    if (!options.find(o => o.accessor === newValue)) {
                        return // this is the create
                    }
                    setExpanded(false)
                    const base = baseName(newSelected)
                    const array = base + "[]"
                    let updated: string[]
                    if (selected.includes(newSelected)) {
                        updated = selected.filter(o => o !== newSelected)
                    } else if (selected.includes(base)) {
                        updated = [...selected.filter(o => o !== base), newSelected]
                    } else if (selected.includes(array)) {
                        updated = [...selected.filter(o => o !== array), newSelected]
                    } else if (allowArray === undefined || allowArray) {
                        openVariantModal(options.filter(o => o.accessor === newValue)[0])
                        return
                    } else {
                        updated = [...selected, newSelected]
                    }
                    setSelected(updated)
                    onChange(updated)
                }}
            >
                {distinctSorted(options.concat(value.map(v => ({ accessor: v }))), o => o.accessor).map(
                    (option, index) => (
                        <SelectOption key={index} value={option.accessor} />
                    )
                )}
            </Select>
            {error && (
                <span
                    style={{
                        display: "inline-block",
                        color: "var(--pf-global--danger-color--100)",
                    }}
                >
                    {error}
                </span>
            )}
            {selected &&
                selected.map(s => {
                    const name = s.endsWith("[]") ? s.substr(0, s.length - 2) : s
                    const distinctSchemaOptions = distinctSorted(
                        options.filter(o => o.accessor === name),
                        (o: Extractor) => o.schema
                    )
                    return (
                        <div key={s} style={{ marginTop: "5px" }}>
                            <span
                                style={{
                                    border: "1px solid #888",
                                    borderRadius: "4px",
                                    padding: "4px",
                                    backgroundColor: "#f0f0f0",
                                }}
                            >
                                {s}
                            </span>{" "}
                            is valid for schemas:{"\u00A0"}
                            {distinctSchemaOptions.map((o, i) => (
                                <React.Fragment key={s + "-" + i}>
                                    <span
                                        style={{
                                            border: "1px solid #888",
                                            borderRadius: "4px",
                                            padding: "4px",
                                            backgroundColor: "#f0f0f0",
                                        }}
                                    >
                                        {o.schema}
                                    </span>
                                    {"\u00A0"}
                                </React.Fragment>
                            ))}
                            {!isReadOnly && s !== "" && (
                                <Button
                                    variant="link"
                                    onClick={() => {
                                        setCreated({ accessor: s })
                                        setDisabledSchemas(
                                            distinctSchemaOptions
                                                .map(o => o.schema)
                                                .filter(schema => !!schema) as string[]
                                        )
                                        setCreateOpen(true)
                                    }}
                                >
                                    <AddCircleOIcon />
                                </Button>
                            )}
                        </div>
                    )
                })}
            <Modal title="Create extractor" isOpen={createOpen} onClose={() => setCreateOpen(false)}>
                <Form isHorizontal={true}>
                    <FormGroup label="Accessor" isRequired={true} fieldId="extractor-accessor">
                        <TextInput
                            value={created.accessor}
                            isRequired
                            id="extractor-accessor"
                            name="extractor-accessor"
                            validated={created.accessor !== "" ? "default" : "error"}
                            onChange={value => setCreated({ ...created, accessor: value })}
                        />
                    </FormGroup>
                    <FormGroup label="Schema" isRequired={true} fieldId="extractor-schema">
                        <SchemaSelect value={created.schema} disabled={disabledSchemas} onChange={onSchemaChange} />
                    </FormGroup>
                    <FormGroup
                        label="JSON path"
                        isRequired={true}
                        fieldId="extractor-jsonpath"
                        validated={!created.validationResult || created.validationResult.valid ? "default" : "error"}
                        helperTextInvalid={created.validationResult?.reason}
                    >
                        <TextInput
                            value={created?.jsonpath || ""}
                            isRequired
                            id="extractor-jsonpath"
                            name="extractor-jsonpath"
                            onChange={value => {
                                if (created.validationTimer) {
                                    clearTimeout(created.validationTimer)
                                }
                                created.validationTimer = window.setTimeout(() => {
                                    if (created.jsonpath) {
                                        testJsonPath(value).then(result => {
                                            created.validationResult = result
                                            setCreated({ ...created })
                                        })
                                    }
                                }, 1000)
                                created.jsonpath = value
                                setCreated({ ...created })
                            }}
                        />
                    </FormGroup>
                    <ActionGroup>
                        <Button
                            variant="primary"
                            onClick={() => {
                                addOrUpdateExtractor(created).then(
                                    ignored => {
                                        setCreateOpen(false)
                                        openVariantModal(created)
                                    },
                                    e => {
                                        dispatch(
                                            alertAction("EXTRACTOR_UPDATE", "Failed to add/update schema extractor.", e)
                                        )
                                    }
                                )
                            }}
                        >
                            Save
                        </Button>
                        <Button
                            variant="secondary"
                            onClick={() => {
                                setCreateOpen(false)
                            }}
                        >
                            Cancel
                        </Button>
                    </ActionGroup>
                </Form>
            </Modal>
            <Modal variant="small" title="Select variant" isOpen={variantOpen} onClose={() => setVariantOpen(false)}>
                <Radio
                    isChecked={variant === 0}
                    id="first-match"
                    name="first-match"
                    label="First match"
                    onChange={() => setVariant(0)}
                />
                <Radio
                    isChecked={variant === 1}
                    id="all-matches"
                    name="all-matches"
                    label="All matches (as array)"
                    onChange={() => setVariant(1)}
                />
                <ActionGroup>
                    <Button
                        variant="primary"
                        onClick={() => {
                            setVariantOpen(false)
                            const base = addedOption ? baseName(addedOption.accessor) : ""
                            const name = variant === 0 ? base : base + "[]"
                            setOptions([...options, { ...addedOption, accessor: name }])
                            const updated = [...selected, name]
                            setSelected(updated)
                            onChange(updated)
                        }}
                    >
                        Select
                    </Button>
                    <Button variant="secondary" onClick={() => setVariantOpen(false)}>
                        Cancel
                    </Button>
                </ActionGroup>
            </Modal>
        </>
    )
}
